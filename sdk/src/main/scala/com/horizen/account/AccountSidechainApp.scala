package com.horizen.account

import akka.actor.ActorRef
import com.google.inject.Inject
import com.google.inject.name.Named
import com.horizen.account.api.http.AccountTransactionApiRoute
import com.horizen.account.block.{AccountBlock, AccountBlockSerializer}
import com.horizen.account.companion.SidechainAccountTransactionsCompanion
import com.horizen.account.storage.{AccountHistoryStorage, AccountStateMetadataStorage}
import com.horizen.{AbstractSidechainApp, SidechainAppEvents, SidechainNodeViewHolderRef, SidechainSettings, SidechainSyncInfoMessageSpec, SidechainTypes}
import com.horizen.api.http._
import com.horizen.block.{SidechainBlockBase, SidechainBlockSerializer}
import com.horizen.box.BoxSerializer
import com.horizen.companion._
import com.horizen.consensus.ConsensusDataStorage
import com.horizen.forge.ForgerRef
import com.horizen.helper.{AccountTransactionSubmitProvider, AccountTransactionSubmitProviderImpl, TransactionSubmitProvider, TransactionSubmitProviderImpl}
import com.horizen.network.SidechainNodeViewSynchronizer
import com.horizen.secret.SecretSerializer
import com.horizen.state.ApplicationState
import com.horizen.storage._
import com.horizen.storage.leveldb.VersionedLevelDbStorageAdapter
import com.horizen.transaction._
import com.horizen.transaction.mainchain.SidechainCreation
import com.horizen.utils.{BlockUtils, BytesUtils, Pair}
import com.horizen.wallet.ApplicationWallet
import com.horizen.websocket.server.WebSocketServerRef
import scorex.core.api.http.ApiRoute
import scorex.core.serialization.ScorexSerializer
import scorex.core.settings.ScorexSettings
import scorex.core.transaction.Transaction
import scorex.core.{ModifierTypeId, NodeViewModifier}

import java.io.File
import java.lang.{Byte => JByte}
import java.util.{HashMap => JHashMap, List => JList}
import scala.collection.immutable.Map
import scala.collection.mutable
import scala.io.{Codec, Source}
import scala.util.{Failure, Success}


class AccountSidechainApp @Inject()
  (@Named("SidechainSettings") sidechainSettings: SidechainSettings,
   @Named("CustomSecretSerializers") customSecretSerializers: JHashMap[JByte, SecretSerializer[SidechainTypes#SCS]],
   @Named("CustomAccountTransactionSerializers") val customAccountTransactionSerializers: JHashMap[JByte, TransactionSerializer[SidechainTypes#SCAT]],
   @Named("CustomApiGroups") customApiGroups: JList[ApplicationApiGroup],
   @Named("RejectedApiPaths") rejectedApiPaths : JList[Pair[String, String]],
  )
  extends AbstractSidechainApp(
    sidechainSettings,
    customSecretSerializers,
    customApiGroups,
    rejectedApiPaths
  )
{

  override type TX = SidechainTypes#SCAT
  override type PMOD = AccountBlock
  override type NVHT = AccountSidechainNodeViewHolder

  override implicit lazy val settings: ScorexSettings = sidechainSettings.scorexSettings

  private val storageList = mutable.ListBuffer[Storage]()

  log.info(s"Starting application with settings \n$sidechainSettings")

  protected lazy val sidechainAccountTransactionsCompanion: SidechainAccountTransactionsCompanion = SidechainAccountTransactionsCompanion(customAccountTransactionSerializers)

  // Deserialize genesis block bytes
  lazy val genesisBlock: AccountBlock = new AccountBlockSerializer(sidechainAccountTransactionsCompanion).parseBytes(
      BytesUtils.fromHexString(sidechainSettings.genesisData.scGenesisBlockHex)
    )

  lazy val sidechainCreationOutput: SidechainCreation = BlockUtils.tryGetSidechainCreation(genesisBlock) match {
    case Success(output) => output
    case Failure(exception) => throw new IllegalArgumentException("Genesis block specified in the configuration file has no Sidechain Creation info.", exception)
  }

  val dataDirAbsolutePath: String = sidechainSettings.scorexSettings.dataDir.getAbsolutePath
  val secretStore = new File(dataDirAbsolutePath + "/secret")
  val metaStateStore = new File(dataDirAbsolutePath + "/state")
  val historyStore = new File(dataDirAbsolutePath + "/history")
  val consensusStore = new File(dataDirAbsolutePath + "/consensusData")

  // Init all storages
  protected val sidechainSecretStorage = new SidechainSecretStorage(
    registerStorage(new VersionedLevelDbStorageAdapter(secretStore)),
    sidechainSecretsCompanion)

  protected val stateMetadataStorage = new AccountStateMetadataStorage(
    registerStorage(new VersionedLevelDbStorageAdapter(metaStateStore)))

  protected val sidechainHistoryStorage = new AccountHistoryStorage(
    registerStorage(new VersionedLevelDbStorageAdapter(historyStore)),
    sidechainAccountTransactionsCompanion,
    params)

  protected val consensusDataStorage = new ConsensusDataStorage(
    registerStorage(new VersionedLevelDbStorageAdapter(consensusStore)))

  // Append genesis secrets if we start the node first time
  if(sidechainSecretStorage.isEmpty) {
    for(secretHex <- sidechainSettings.wallet.genesisSecrets)
      sidechainSecretStorage.add(sidechainSecretsCompanion.parseBytes(BytesUtils.fromHexString(secretHex)))

    for(secretSchnorr <- sidechainSettings.withdrawalEpochCertificateSettings.signersSecrets)
      sidechainSecretStorage.add(sidechainSecretsCompanion.parseBytes(BytesUtils.fromHexString(secretSchnorr)))
  }

  override val nodeViewHolderRef: ActorRef = AccountNodeViewHolderRef(
    sidechainSettings,
    sidechainHistoryStorage,
    consensusDataStorage,
    stateMetadataStorage,
    sidechainSecretStorage,
    params,
    timeProvider,
    genesisBlock
    ) // TO DO: why not to put genesisBlock as a part of params? REVIEW Params structure

  def modifierSerializers: Map[ModifierTypeId, ScorexSerializer[_ <: NodeViewModifier]] =
    Map(SidechainBlockBase.ModifierTypeId -> new AccountBlockSerializer(sidechainAccountTransactionsCompanion),
      Transaction.ModifierTypeId -> sidechainAccountTransactionsCompanion)

  override val nodeViewSynchronizer: ActorRef =
    actorSystem.actorOf(SidechainNodeViewSynchronizer.props(networkControllerRef, nodeViewHolderRef,
        SidechainSyncInfoMessageSpec, settings.network, timeProvider, modifierSerializers))

  // If the web socket connector can be started, maybe we would to associate a client to the web socket channel created by the connector
  if(connectorStarted.isSuccess)
    communicationClient.setWebSocketChannel(webSocketConnector)
  else if (sidechainSettings.withdrawalEpochCertificateSettings.submitterIsEnabled)
    throw new RuntimeException("Unable to connect to websocket. Certificate submitter needs connection to Mainchain.")

  // Init Forger with a proper web socket client
  val sidechainBlockForgerActorRef: ActorRef = ??? //ForgerRef("Forger", sidechainSettings, nodeViewHolderRef,  mainchainSynchronizer,
  //  sidechainAccountTransactionsCompanion, timeProvider, params)

  // Init Transactions and Block actors for Api routes classes
  val sidechainTransactionActorRef: ActorRef = SidechainTransactionActorRef(nodeViewHolderRef)
  val sidechainBlockActorRef: ActorRef = SidechainBlockActorRef("AccountBlock", sidechainSettings, nodeViewHolderRef, sidechainBlockForgerActorRef)

  var coreApiRoutes: Seq[SidechainApiRoute] = Seq[SidechainApiRoute](
    MainchainBlockApiRoute(settings.restApi, nodeViewHolderRef),
    SidechainBlockApiRoute(settings.restApi, nodeViewHolderRef, sidechainBlockActorRef, sidechainBlockForgerActorRef),
    SidechainNodeApiRoute(peerManagerRef, networkControllerRef, timeProvider, settings.restApi, nodeViewHolderRef),
    AccountTransactionApiRoute(settings.restApi, nodeViewHolderRef, sidechainTransactionActorRef, sidechainAccountTransactionsCompanion, params),
    SidechainWalletApiRoute(settings.restApi, nodeViewHolderRef),
    SidechainSubmitterApiRoute(settings.restApi, certificateSubmitterRef, nodeViewHolderRef),
  )

  val transactionSubmitProvider : AccountTransactionSubmitProvider = new AccountTransactionSubmitProviderImpl(sidechainTransactionActorRef)

  // In order to provide the feature to override core api and exclude some other apis,
  // first we create custom reject routes (otherwise we cannot know which route has to be excluded), second we bind custom apis and then core apis
  override val apiRoutes: Seq[ApiRoute] = Seq[SidechainApiRoute]()
    .union(rejectedApiRoutes)
    .union(applicationApiRoutes)
    .union(coreApiRoutes)

  override val swaggerConfig: String = Source.fromResource("api/sidechainApi.yaml")(Codec.UTF8).getLines.mkString("\n")

  override def stopAll(): Unit = {
    super.stopAll()
    storageList.foreach(_.close())
  }

  private def registerStorage(storage: Storage) : Storage = {
    storageList += storage
    storage
  }

  def getTransactionSubmitProvider: AccountTransactionSubmitProvider = transactionSubmitProvider

  actorSystem.eventStream.publish(SidechainAppEvents.SidechainApplicationStart)
}

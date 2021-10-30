package com.horizen.network

import akka.actor.{ActorContext, ActorRef}
import scorex.core.consensus.History.Older
import scorex.core.network.{ConnectedPeer, SyncTracker}
import scorex.core.settings.NetworkSettings
import scorex.core.utils.TimeProvider
import scorex.core.utils.TimeProvider.Time
import scala.collection.mutable
import scala.concurrent.ExecutionContext


class SidechainSyncTracker (nvsRef: ActorRef,
                            context: ActorContext,
                            networkSettings: NetworkSettings,
                            timeProvider: TimeProvider)(implicit ec: ExecutionContext)
  extends SyncTracker(nvsRef: ActorRef,
    context: ActorContext,
    networkSettings: NetworkSettings,
    timeProvider: TimeProvider) {



  private var olderStatusesMap = mutable.Map[ConnectedPeer, SidechainSyncStatus]()
  private var failedStatusesMap = mutable.Map[ConnectedPeer, SidechainFailedSync]()
  var betterNeighbourHeight = -1
  var myHeight = -1 // DIRAC TODO update first on processSync after every succesfulModifier applied

  def updateSyncStatus(peer: ConnectedPeer, syncStatus: SidechainSyncStatus): Unit = {
    updateStatus(peer,syncStatus.historyCompare)
    if (syncStatus.historyCompare == Older){
      log.info(s"updating syncStatus , height = ${syncStatus.otherNodeDeclaredHeight}")
      if(olderStatusesMap.contains(peer)){ // update only the heights
        olderStatusesMap(peer).otherNodeDeclaredHeight = syncStatus.otherNodeDeclaredHeight
        olderStatusesMap(peer).myOwnHeight = syncStatus.myOwnHeight
      }
      else{
        olderStatusesMap += peer -> syncStatus
      }
      log.info(s"updateSyncStatus : olderStatusesMap = ${olderStatusesMap.toSeq.toString()} ,size = ${olderStatusesMap.size}")
      // DIRAC not sure if correct, just to have the best height we got through the sync phase
      betterNeighbourHeight =
        if (syncStatus.otherNodeDeclaredHeight > betterNeighbourHeight)
          syncStatus.otherNodeDeclaredHeight
        else betterNeighbourHeight
      myHeight = syncStatus.myOwnHeight
    }else{
      // DIRAC TODO update to tell that's not older anymore
    }

  }

  // DIRAC TODO what if We don't have a peer on the other side....
  def updateForFailing(peer: ConnectedPeer, sidechainFailedSync: SidechainFailedSync):Unit ={
    failedStatusesMap += peer -> sidechainFailedSync
  }


  def updateStatusWithLastSyncTime(peer:ConnectedPeer, time: Time): Unit = {
    // DIRAC TODO at the beginning have to check if it exist, if not u have to put @ASKSasha
    // try to do with catching exception java.util.NoSuchElementException: @ASKSasha
    olderStatusesMap(peer).lastTipSyncTime = time
    log.info(s"updateStatusWithLastSyncTime peer = $peer  -  olderStatusesMap(peer) = ${olderStatusesMap(peer)} ")
  }

  def updateStatusWithMyHeight(peer: ConnectedPeer):Unit = {
    olderStatusesMap(peer).myOwnHeight += 1
    myHeight+=1
  }
}

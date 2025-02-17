package com.horizen.proposition

import com.fasterxml.jackson.databind.JsonNode
import com.horizen.secret.{PrivateKey25519Creator, Secret}
import com.horizen.serialization.ApplicationJsonSerializer
import com.horizen.utils.Ed25519
import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.Test
import org.scalatestplus.junit.JUnitSuite

import scala.collection.JavaConverters._
import scorex.core.utils.ScorexEncoder

class PublicKey25519PropositionScalaTest
  extends JUnitSuite
{

  @Test
  def testToJson(): Unit = {
    val seed = "12345".getBytes
    val keyPair = Ed25519.createKeyPair(seed)
    val privateKey = keyPair.getKey
    val publicKey = keyPair.getValue

    val prop1 = new PublicKey25519Proposition(publicKey)

    val serializer = ApplicationJsonSerializer.getInstance()
    serializer.setDefaultConfiguration()

    val jsonStr = serializer.serialize(prop1)

    val node : JsonNode = serializer.getObjectMapper().readTree(jsonStr)

    assertEquals("Json must contain only 1 publicKey.",
      1, node.findValues("publicKey").size())
    assertEquals("PublicKey json value must be the same.",
      ScorexEncoder.default.encode(prop1.pubKeyBytes()), node.path("publicKey").asText())

  }

  @Test
  def testProvable(): Unit = {
    val privateKey1 = PrivateKey25519Creator.getInstance().generateSecret("seed1".getBytes)
    val privateKey2 = PrivateKey25519Creator.getInstance().generateSecret("seed2".getBytes)
    val publicKey1 = privateKey1.publicImage();

    var provableCheckResult = publicKey1.canBeProvedBy( List[Secret](privateKey1, privateKey2).asJava);
    assertTrue(provableCheckResult.canBeProved)
    assertEquals(provableCheckResult.secretsNeeded().size(), 1)
    assertEquals(provableCheckResult.secretsNeeded().get(0), privateKey1)

    //negative test
    val privateKey3 = PrivateKey25519Creator.getInstance().generateSecret("seed3".getBytes)
    val publicKey3 = privateKey3.publicImage();
    provableCheckResult = publicKey3.canBeProvedBy( List[Secret](privateKey1, privateKey2).asJava);
    assertFalse(provableCheckResult.canBeProved)
    assertEquals(provableCheckResult.secretsNeeded().size(), 0)
  }

}

package JSONFormat

import core.InteractiveActor._
import core.InteractiveActor.{InputDockerJSON, InputDockerJSONProtocol, PortMapping, PortMappingProtocol}
import org.scalatest.{FeatureSpec, FlatSpec, GivenWhenThen, Matchers}
import spray.json._


/**
  * Created by Axel Roy on 27.06.17.
  */




class JSONFormattingTests extends FlatSpec with Matchers{
  // Verification of the JSON format for the Docker Interactive TPOT container
  import InputDockerJSONProtocol._

  val values = List[Int](12,13,14,14)
  val type_ = "training"
  val query = "SELECT score_test1 from linreg_sample;"
  val entry = InputDockerJSON(type_, values, query)

  entry.toJson.compactPrint.trim should be ("""{"type_":"training","values":[12,13,14,14],"query":"SELECT score_test1 from linreg_sample;"}""")
  print(entry.toJson.compactPrint.trim + "\n")

  ////////////////////////////////////////////////////////
  //  Tests for the JSON file sent via HTTP to Marathon //
  ////////////////////////////////////////////////////////

  /*
  Example of structure to test

  {
  "container": {
    "type": "DOCKER",
    "docker": {
      "network": "HOST",
      "image": "group/image"
    },
    "volumes": [
      {
        "containerPath": "/etc/a",
        "hostPath": "/var/data/a",
        "mode": "RO"
      },
      {
        "containerPath": "/etc/b",
        "hostPath": "/var/data/b",
        "mode": "RW"
      }
    ]
  }
}

   */

  import PortMappingProtocol._
  import VolumeProtocol._

  // Subpart - portmapping section
  val portMapping = new PortMapping(80,81,82,"udp")
  val port_JSON = portMapping.toJson

  port_JSON.compactPrint.trim should be ("""{"containerPort" :80,"hostPort":81,"servicePort":82,"protocol":"udp"}""".replaceAll(" ", ""))
  print(port_JSON.compactPrint.trim + "\n")

  // Subpart - Volume configuration
  val volume = new Volume("/etc/b", "/var/data/b", "RW")

  volume.toJson.compactPrint.trim should be ("""{"containerPath": "/etc/b", "hostPath": "/var/data/b", "mode": "RW"}""".replaceAll(" ", ""))
  print(volume.toJson.compactPrint.trim + "\n")




  // Subpart - Volumes (list of volume)
//  val volume1 = new Volume("/etc/a", "/var/data/a", "RW")
//  val volume2 = new Volume("/etc/a", "/var/data/a", "RO")
//  val volumeList = List(volume1, volume2)
//  val volumes = new Volumes(volumeList)
//
//  print(volumes.toJson.compactPrint.trim + "\n")
//  volumes.toJson.compactPrint.trim should be ("""[{"containerPath": "/etc/a", "hostPath": "/var/data/a", "mode": "RO"},{"containerPath": "/etc/b", "hostPath": "/var/data/b", "mode": "RW"}]""".replaceAll(" ", ""))


}

package JSONFormat

//import core.InteractiveActor.{InputDockerJSON, InputDockerJSONProtocol, PortMapping, PortMappingProtocol}
import models.{Container, EnvironmentVariable, Volume}
import org.scalatest.{FlatSpec, Matchers}
import spray.json._


/**
  * Created by Axel Roy on 27.06.17.
  */



class JSONFormattingTests extends FlatSpec with Matchers{
  // Verification of the JSON format for the Chronos JSON formatting
  import models.ChronosJob

  var env: List[EnvironmentVariable] = List(
    new EnvironmentVariable("IN_JDBC_URL", "jdbc:postgresql://172.17.0.1:65432/postgres"),
    new EnvironmentVariable("IN_JDBC_USER", "postgres"),
    new EnvironmentVariable("IN_JDBC_PASSWORD", "test"),
    new EnvironmentVariable("OUT_JDBC_URL", "jdbc:postgresql://172.17.0.1:5432/postgres"),
    new EnvironmentVariable("OUT_JDBC_USER", "postgres"),
    new EnvironmentVariable("OUT_JDBC_PASSWORD", "test"),
    new EnvironmentVariable("PARAM_meta", "{}")

  )

  val volume: Volume = new Volume("/home/user/docker-volume", "/docker-volume/", "RW")
  val container: Container = new Container("DOCKER", "axelroy/python-mip-tpot", List(Volume("/docker-volume/", "/home/user/docker-volume/", "RW")), None)
  val job: ChronosJob = new ChronosJob("R1//PT24H", "PT5M", "name", "train", false, "root", container, "0.5", "512", List(), false, "admin@mip.chuv.ch", env)

//  print(job)
//  print(volume.toJson.prettyPrint)
//  print(container.toJson.prettyPrint)
  print(job.toJson.prettyPrint.trim)

//  val values = List[Int](12,13,14,14)
//  val type_ = "training"
//  val query = "SELECT score_test1 from linreg_sample;"
//  val entry = InputDockerJSON(type_, values, query)
//
//  entry.toJson.compactPrint.trim should be ("""{"type_":"training","values":[12,13,14,14],"query":"SELECT score_test1 from linreg_sample;"}""")
//  print(entry.toJson.compactPrint.trim + "\n")

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

//  import PortMappingProtocol._
//  import VolumeProtocol._
//
//  // Subpart - portmapping section
//  val portMapping = new PortMapping(80,81,82,"udp")
//  val port_JSON = portMapping.toJson
//
//  port_JSON.compactPrint.trim should be ("""{"containerPort" :80,"hostPort":81,"servicePort":82,"protocol":"udp"}""".replaceAll(" ", ""))
//  print(port_JSON.compactPrint.trim + "\n")
//
//  // Subpart - Volume configuration
//  val volume = new Volume("/etc/b", "/var/data/b", "RW")
//
//  volume.toJson.compactPrint.trim should be ("""{"containerPath": "/etc/b", "hostPath": "/var/data/b", "mode": "RW"}""".replaceAll(" ", ""))
//  print(volume.toJson.compactPrint.trim + "\n")

// Subpart - Volumes (list of volume)
//  val volume1 = new Volume("/etc/a", "/var/data/a", "RW")
//  val volume2 = new Volume("/etc/a", "/var/data/a", "RO")
//  val volumeList = List(volume1, volume2)
//  val volumes = new Volumes(volumeList)
//
//  print(volumes.toJson.compactPrint.trim + "\n")
//  volumes.toJson.compactPrint.trim should be ("""[{"containerPath": "/etc/a", "hostPath": "/var/data/a", "mode": "RO"},{"containerPath": "/etc/b", "hostPath": "/var/data/b", "mode": "RW"}]""".replaceAll(" ", ""))

}

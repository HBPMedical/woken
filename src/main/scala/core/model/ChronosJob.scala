package models

import models.ChronosJob.jsonFormat3
import spray.json.{DefaultJsonProtocol, RootJsonFormat}


case class Container(
  `type`: String,
  image: String,
  volumes: List[Volume],         // Only one volume at the moment
  network: Option[String] = None
)

case class EnvironmentVariable(
   name: String,
   value: String
)

case class Uri(
  uri: String
)

case class Volume(
                   containerPath: String,      // i.e /home/user/path/
                   hostPath: String,           // i.e /volume/
                   mode:String)                //RW, RO - Read-write, Read Only


case class ChronosJob(
   schedule: String,
   epsilon: String,
   name: String,
   command: String,
   shell: Boolean,
   runAsUser: String,
   container: Container,
   cpus: String,
   mem: String,
   uris: List[Uri],
   async: Boolean,
   owner: String,
   environmentVariables: List[EnvironmentVariable]
)
object Volume extends DefaultJsonProtocol{
  implicit val volumeFormat = jsonFormat3(Volume.apply)
}

object Container extends DefaultJsonProtocol{
  implicit val containerFormat = jsonFormat4(Container.apply)
}


object ChronosJob extends DefaultJsonProtocol {
  implicit val environmentVariableFormat = jsonFormat2(EnvironmentVariable.apply)
  implicit val uriFormat = jsonFormat1(Uri.apply)
  implicit val chronosJobFormat: RootJsonFormat[ChronosJob] = jsonFormat13(ChronosJob.apply)
}
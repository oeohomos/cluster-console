package clusterconsole.http

import akka.actor._
import akka.pattern.ask
import akka.http.scaladsl.marshalling.{ PredefinedToEntityMarshallers, ToEntityMarshaller }
import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity, MediaTypes }
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ RequestContext, Route }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Flow, FlowGraph, Merge, Source }
import akka.util.Timeout
import clusterconsole.clustertracking.{ GetDiscovered, TrackDiscovered, IsDiscovered, ClusterAware }
import clusterconsole.core.LogF

import scala.collection.mutable
import scala.collection.mutable.{ Map => MutableMap }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

trait ClusterConsoleRoutes extends LogF { this: Actor =>

  def socketPublisherRouter: ActorRef

  lazy val clusterDiscoveryService = new ClusterDiscoveryService(context, socketPublisherRouter)

  implicit def marshaller: ToEntityMarshaller[String] =
    PredefinedToEntityMarshallers.stringMarshaller(MediaTypes.`text/html`)

  def routes(implicit mat: ActorMaterializer): Route = initialPageRoute

  def httpRequest2String(req: RequestContext)(implicit mat: ActorMaterializer): Future[String] =
    req.request.entity.withContentType(ContentTypes.`application/json`).toStrict(1 second).map(b => new String(b.data.toArray))

  def initialPageRoute(implicit mat: ActorMaterializer): Route =
    get {
      pathSingleSlash {
        complete(
          Page.main("Cluster Console").toString()
        )
      } ~ pathPrefix("srcmaps") {
        getFromDirectory("../")
      } ~ {
        getFromResourceDirectory("web")
      } ~ path("api") {

        complete("")

      } ~ path("events") {
        handleWebsocketMessages(clusterSocketFlow(socketPublisherRouter))
      }
    } ~ post {
      path("api" / Segments) { segments =>
        extract(httpRequest2String) { dataExtractor =>
          complete {
            for {
              data <- dataExtractor
              response <- AutowireServer.route[Api](clusterDiscoveryService)(autowire.Core.Request(
                segments, upickle.read[Map[String, String]](data.logDebug("***********  data: " + _)))
              )
            } yield HttpEntity(response)
          }
        }
      }
    }

  def clusterSocketFlow(router: ActorRef): Flow[Message, Message, Unit] = {
    Flow() { implicit builder =>
      import FlowGraph.Implicits._

      "clusterSocketFlow".logDebug("here in " + _)

      // create an actor source
      val source = Source.actorPublisher[String](Props(classOf[ClusterPublisher], router))
      val filter = builder.add(Flow[String].filter(_ => false))
      val merge = builder.add(Merge[String](2))

      val mapMsgToString = builder.add(Flow[Message].map[String] { msg => "" })
      val mapStringToMsg = builder.add(Flow[String].map[Message](x => TextMessage.Strict(x)))

      val clusterEventsSource = builder.add(source)

      // connect the graph
      //      mapMsgToResponse ~> filter ~> merge
      mapMsgToString ~> filter ~> merge
      clusterEventsSource ~> merge ~> mapStringToMsg

      // expose ports
      (mapMsgToString.inlet, mapStringToMsg.outlet)

    }
  }

  override def receive: Receive = {
    case Terminated(ref) =>
      logger.logDebug(s"Died: $ref" + _)
      //TODO: refactor
      clusterDiscoveryService.discovering.get(ref).foreach(d => socketPublisherRouter ! ClusterUnjoin(d.system, d.seedNodes))
      clusterDiscoveryService.discovering -= ref
  }

}

class ClusterDiscoveryService(context: ActorContext, socketPublisherRouter: ActorRef) extends Api with LogF {

  val discovering: MutableMap[ActorRef, DiscoveryBegun] = MutableMap.empty

  val trackDiscovered: ActorRef = context.system.actorOf(TrackDiscovered.props)

  def discover(systemName: String, seedNodes: List[HostPort]): Option[DiscoveryBegun] = {

    val discovered = getDiscoveredFromTracked
    if (!discovered.exists(_.system == systemName)) {
      val newSystemActor = context.system.actorOf(ClusterAware.props(systemName, seedNodes, socketPublisherRouter, trackDiscovered))
      val value = DiscoveryBegun(systemName, seedNodes)
      discovering += newSystemActor -> value
      context.watch(newSystemActor)
      systemName.logDebug("*********************************************************  discover  " + _)
      Some(value)
    } else None

  }

  def getDiscovering(): Seq[DiscoveryBegun] = {
    discovering.values.toList.filter(db => !getDiscovered().exists(dc => db.system == dc.system))
  }

  private def getDiscoveredFromTracked: Set[DiscoveredCluster] = {
    implicit val timeout = Timeout(3 seconds)
    val futureDiscovered: Future[Set[DiscoveredCluster]] = (trackDiscovered ? GetDiscovered).mapTo[Set[DiscoveredCluster]]
    Await.result(futureDiscovered, 2 seconds)
  }

  private def getDiscoveredFromTracked(system: String): Option[DiscoveredCluster] = {
    implicit val timeout = Timeout(3 seconds)
    val futureMaybeDiscovered: Future[Option[DiscoveredCluster]] = (trackDiscovered ? GetDiscovered(system)).mapTo[Option[DiscoveredCluster]]
    Await.result(futureMaybeDiscovered, 2 seconds)
  }

  def getDiscovered: Set[DiscoveredCluster] = {
    getDiscoveredFromTracked.logDebug("getDiscovered [" + _.toList.size + "]")
  }

  def getCluster(system: String): Option[DiscoveredCluster] = getDiscoveredFromTracked(system)

}

object AutowireServer extends autowire.Server[String, upickle.Reader, upickle.Writer] {
  def read[Result: upickle.Reader](p: String) = upickle.read[Result](p)
  def write[Result: upickle.Writer](r: Result) = upickle.write(r)
}


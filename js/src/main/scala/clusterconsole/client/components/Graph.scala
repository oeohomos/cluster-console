package clusterconsole.client.components

import clusterconsole.client.components.GraphNode.State
import clusterconsole.client.d3.D3.Selection
import clusterconsole.client.services.{ ClusterStore, ClusterStoreActions }
import clusterconsole.client.style.GlobalStyles
import clusterconsole.http.{ ClusterMember, ClusterDependency, RoleDependency, DiscoveredCluster }
import japgolly.scalajs.react.extra.OnUnmount
import japgolly.scalajs.react.vdom.{ Attrs, SvgAttrs }
import org.scalajs.dom.raw.SVGCircleElement
import rx._

import clusterconsole.client.d3.Layout._
import clusterconsole.client.modules._
import japgolly.scalajs.react.{ ReactComponentB, ReactNode }
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.all.svg._
import japgolly.scalajs.react.vdom.prefix_<^._
import scala.scalajs.js
import clusterconsole.client.d3._
import js.JSConverters._
import clusterconsole.client.services.Logger._

import scala.scalajs.js

trait ClusterGraphNode extends GraphNode {
  var virtualHost: String = js.native
  var host: String = js.native
  var port: Int = js.native
  var roles: String = js.native
  var status: String = js.native

}

object ClusterGraphNode {

  def label(n: ClusterGraphNode): String = n.host + ":" + n.port

  def apply(m: ClusterMember,
    index: Double,
    x: Double,
    y: Double,
    px: Double,
    py: Double,
    fixed: Boolean,
    weight: Double): ClusterGraphNode =
    js.Dynamic.literal(
      "virtualHost" -> "",
      "host" -> m.address.host,
      "port" -> m.address.port,
      "roles" -> m.roles.mkString(","),
      "status" -> m.state.toString,
      "name" -> m.label,
      "index" -> index,
      "x" -> x,
      "y" -> y,
      "px" -> px,
      "py" -> py,
      "fixed" -> fixed,
      "weight" -> weight
    ).asInstanceOf[ClusterGraphNode]

  def host(m: ClusterMember,
    index: Double,
    x: Double,
    y: Double,
    px: Double,
    py: Double,
    fixed: Boolean,
    weight: Double): ClusterGraphNode =
    js.Dynamic.literal(
      "virtualHost" -> "",
      "host" -> m.address.host,
      "port" -> 0,
      "roles" -> "",
      "status" -> "",
      "name" -> "",
      "index" -> index,
      "x" -> x,
      "y" -> y,
      "px" -> px,
      "py" -> py,
      "fixed" -> fixed,
      "weight" -> weight
    ).asInstanceOf[ClusterGraphNode]

  def port(m: ClusterMember,
    index: Double,
    x: Double,
    y: Double,
    px: Double,
    py: Double,
    fixed: Boolean,
    weight: Double): ClusterGraphNode =
    js.Dynamic.literal(
      "virtualHost" -> m.address.host,
      "host" -> "",
      "port" -> m.address.port,
      "roles" -> m.roles.mkString(","),
      "status" -> m.state.toString,
      "name" -> "",
      "index" -> index,
      "x" -> x,
      "y" -> y,
      "px" -> px,
      "py" -> py,
      "fixed" -> fixed,
      "weight" -> weight
    ).asInstanceOf[ClusterGraphNode]

}

trait ClusterGraphLink extends GraphLink {
  var sourceHost: String = js.native
  var targetHost: String = js.native
}

trait ClusterGraphRoleLink extends ClusterGraphLink {
  var index: Int = js.native
}

object LegendColors {

  lazy val colors: List[String] = List("#136C90", "#9D4CD2", "#116126", "#D2902A", "#EA4040", "#6746EC")
}

object ClusterDependencyLegend {

  case class Props(dep: RoleDependency, index: Int, selected: Boolean, selectDep: (RoleDependency, Boolean) => Unit)

  case class State(selected: Boolean)

  class Backend(t: BackendScope[Props, State]) {
    def select = {
      t.modState(_.copy(selected = !t.state.selected))
      t.props.selectDep(t.props.dep, !t.state.selected)
    }
  }

  val component = ReactComponentB[Props]("ClusterDependencyLegend")
    .initialStateP(P => State(P.selected))
    .backend(new Backend(_))
    .render { (P, S, B) =>

      val label = P.dep.tpe.name + ": " + P.dep.roles.mkString(",") + " depends on " + P.dep.dependsOn.mkString(",")

      val rectwidth = (label.length * 9) + 20

      g({
        import japgolly.scalajs.react.vdom.all._
        onClick --> B.select
      })(rect(width := rectwidth.toString, height := "40", fill := {
        if (S.selected) {
          LegendColors.colors(P.index % 5)
        } else {
          GlobalStyles.leftNavBackgrounColor
        }
      }, x := 0, y := (P.index * 45) + 5, stroke := GlobalStyles.textColor),

        text(x := 10, y := (P.index * 45) + 30, fill := GlobalStyles.textColor, fontSize := "15px", fontFamily := "Courier")(label)
      )
    }.build

  def apply(dep: RoleDependency, index: Int, selected: Boolean, selectDep: (RoleDependency, Boolean) => Unit) =
    component(Props(dep, index, selected, selectDep))
}

object GraphNode {

  case class Props(node: ClusterGraphNode, force: ForceLayout, mode: Mode)

  case class State(selected: Boolean)

  class Backend(t: BackendScope[Props, State]) {
    def select = t.modState(_.copy(selected = !t.state.selected))
  }

  val component = ReactComponentB[Props]("GraphNode")
    .initialStateP(P => State(false))
    .backend(new Backend(_))
    .render { (P, S, B) =>
      g(
        circle(Attrs.cls := "node", Attrs.id := P.node.index, r := getRadius(P.mode, P.node), cx := P.node.x, cy := P.node.y,
          fill := {

            if (S.selected) {
              "#EEE"
            } else {
              P.node.status match {
                case "Up" => GlobalStyles.nodeUpColor
                case "Unreachable" => GlobalStyles.nodeUnreachableColor
                case "Removed" => GlobalStyles.nodeRemovedColor
                case "Exited" => GlobalStyles.nodeRemovedColor
                case _ => GlobalStyles.nodeUpColor
              }

            }

          }, stroke := "#fff", strokeWidth := "1.px5"
        //        , {
        //            import japgolly.scalajs.react.vdom.all._
        //            onClick --> B.select
        //
        //          }
        ),
        getTextNodes(P.mode, P.node)
      )

    }.componentDidMount { scope =>

      val drag1 = d3.behavior.drag()
      //      drag1.origin(() => js.Array(0, 0))
      //      d3.select(scope.getDOMNode().firstChild).call(scope.props.force.drag())
      d3.select(scope.getDOMNode().firstChild).call(drag1)

    }.build

  def getRadius(mode: Mode, n: ClusterGraphNode): String = mode match {
    case Nodes =>
      if (n.host.length > 0) {
        "60"
      } else {
        "30"
      }
    case _ => "30"
  }

  def getTextNodes(mode: Mode, n: ClusterGraphNode): ReactNode = mode match {
    case Nodes =>
      if (n.host.length > 0) {
        g(
          text(x := n.x - 30, y := n.y - 55, fill := "white", fontSize := "18px")(n.host)
        )

      } else {
        g(
          text(x := n.x - 30, y := n.y - 55, fill := "white", fontSize := "18px")(n.port),
          text(x := n.x - 30, y := n.y - 35, fill := "#D5EFD5", fontSize := "18px")(n.roles)
        )
      }
    case _ => g(
      text(x := n.x - 30, y := n.y - 55, fill := "white", fontSize := "18px")(n.host + ":" + n.port),
      text(x := n.x - 30, y := n.y - 35, fill := "#D5EFD5", fontSize := "18px")(n.roles)
    )
  }

  def apply(node: ClusterGraphNode, force: ForceLayout, mode: Mode) = component(Props(node, force, mode))
}

object GraphLink {

  case class Props(link: ClusterGraphLink, key: Int, mode: Mode)

  val component = ReactComponentB[Props]("GraphLink")
    .render { P =>

      P.mode match {
        case Members =>
          line(
            Attrs.cls := "link",
            x1 := P.link.source.x,
            y1 := P.link.source.y,
            x2 := P.link.target.x,
            y2 := P.link.target.y,
            stroke := "#999",
            strokeOpacity := ".6",
            strokeWidth := "1",
            strokeDasharray := "5,5")
        case Roles =>

          val roleLink = P.link.asInstanceOf[ClusterGraphRoleLink]

          line(
            Attrs.cls := "link",
            x1 := P.link.source.x,
            y1 := P.link.source.y,
            x2 := P.link.target.x,
            y2 := P.link.target.y,
            stroke := LegendColors.colors(roleLink.index % 5),
            strokeOpacity := "1",
            strokeWidth := "5")
        case Nodes =>
          line(
            Attrs.cls := "link",
            x1 := P.link.source.x,
            y1 := P.link.source.y,
            x2 := P.link.target.x,
            y2 := P.link.target.y,
            stroke := "#999",
            strokeOpacity := "1",
            strokeWidth := "1")

      }

    }.build

  def apply(link: ClusterGraphLink, key: Int, mode: Mode) = component(Props(link, key, mode))
}

object Graph {

  import clusterconsole.client.style.CustomTags._

  case class Props(system: String, mode: Mode, width: Double, height: Double,
    store: ClusterStore, fixedMap: Boolean)

  case class State(nodes: Seq[ClusterGraphNode],
    links: Seq[ClusterGraphLink],
    force: ForceLayout)

  def drawLinks(links: Seq[ClusterGraphLink], mode: Mode): ReactNode =
    g(links.zipWithIndex.map { case (eachLink, i) => GraphLink(eachLink, i, mode) })

  def drawNodes(nodes: Seq[ClusterGraphNode], force: ForceLayout, mode: Mode): Seq[ReactNode] =
    nodes.zipWithIndex.map {
      case (node, i) =>
        GraphNode(node, force, mode)
    }

  def drawDeps(roles: Seq[(RoleDependency, Boolean)], select: (RoleDependency, Boolean) => Unit): Seq[ReactNode] =
    roles.zipWithIndex.map {
      case ((dep, selected), i) => ClusterDependencyLegend(dep, i, selected, select)
    }

  class Backend(t: BackendScope[Props, State]) extends RxObserver(t) {

    def mounted(): Unit = {
      react(t.props.store.getSelectedCluster, updateGraph)
      react(t.props.store.getSelectedDeps, updateLinkDeps)
    }

    def selectDep(rd: RoleDependency, selected: Boolean) = {

      log.debug("selectDep " + rd.tpe.name + " " + selected)

      t.props.store.getSelectedCluster().foreach(cluster =>
        ClusterStoreActions.selectRoleDependency(cluster.system, rd, selected)
      )

    }

    def updateLinkDeps(c: Map[String, List[RoleDependency]]) = {
      t.props.store.getSelectedCluster().foreach(cluster =>
        c.get(cluster.system).foreach(deps =>
          t.modState { s =>
            val links: Seq[ClusterGraphLink] = getLinks(t.state.nodes, t.props.mode, cluster, deps)
            val nodeUpdateState = s.copy(nodes = t.state.nodes, force = s.force.nodes(t.state.nodes.toJsArray).start())
            nodeUpdateState.copy(links = links)
            s.copy(links = links)
          }
        )
      )

    }

    def updateGraph(c: Option[DiscoveredCluster]) = {

      c.fold[Unit]({})(cluster => {

        log.debug("updateGraph")

        val existingIndexes = t.state.nodes.map(_.index).toSet

        val incomingNodes: List[ClusterGraphNode] =
          t.props.mode match {
            case Nodes => Nil

            case _ =>
              val currentNodesMap = t.state.nodes.map(e => (ClusterGraphNode.label(e), e)).toMap
              cluster.members.toList.map { node =>
                currentNodesMap.get(node.address.label).fold(
                  ClusterGraphNode(node, getNewIndex(existingIndexes, 1), 450, 450, 450, 450, false, 0)
                )(cn => {
                    val fixedList = t.props.store.getDiscoveredClusterNodes().getOrElse(cluster.system, Nil)
                    fixedList.find(_.host == node.address.label).fold(
                      ClusterGraphNode(node, cn.index, cn.x, cn.y, cn.px, cn.py, cn.fixed, cn.weight)
                    )(fixedNode => {
                        log.debug("^^^^^^^^^^^^^  fixedNode  " + fixedNode.x)
                        ClusterGraphNode(node, fixedNode.index, fixedNode.x, fixedNode.y, fixedNode.px, fixedNode.py, fixedNode.fixed, fixedNode.weight)
                      })
                  })
              }

          }

        t.modState { s =>
          val links: Seq[ClusterGraphLink] = {

            t.props.mode match {
              case Roles =>
                getLinks(incomingNodes, t.props.mode, cluster, t.props.store.getSelectedDeps().getOrElse(cluster.system, Nil))

              case _ => getLinks(incomingNodes, t.props.mode, cluster, t.props.store.getSelectedDeps().get(cluster.system).getOrElse(Nil))
            }
          }
          val nodesToForce = incomingNodes.filter(_.fixed == false)
          val nodeUpdateState = s.copy(nodes = incomingNodes, force = s.force.nodes(nodesToForce.toJsArray).start())
          nodeUpdateState.copy(links = links)
        }
      })
    }

    def renderTick() = {
      val newNodes: List[ClusterGraphNode] = t.state.force.nodes().toList
      val notFixed = newNodes.filter(_.fixed == false)
      val fixed = t.state.nodes.filter(_.fixed == true)
      t.modState(s => s.copy(nodes = notFixed ++ fixed))
    }

    def start() = {
      t.modState { s =>
        val nodesToForce = t.state.nodes.filter(_.fixed == false)
        val firstState = s.copy(force = s.force.nodes(nodesToForce.toJsArray).start())
        firstState.copy(force = s.force.on("tick", () => renderTick))
      }
    }

    def startfixed() = {
      t.modState { s =>
        val nodesToForce = t.state.nodes.filter(_.fixed == false)
        val firstState = s.copy(force = s.force.nodes(nodesToForce.toJsArray).start())
        (1 until 150).foreach(i => t.state.force.tick())
        firstState.copy(force = s.force.on("tick", () => renderTick))
      }
    }

    def resume() = {

      t.modState { s =>
        val firstState = s.copy(force = s.force.nodes(t.state.nodes.toJsArray).resume())
        firstState.copy(force = s.force.on("tick", () => renderTick))
      }

    }

    def dragEnd(a: js.Any, b: Double): js.Any = {
      t.state.nodes.find(_.index == b).foreach(node =>
        ClusterStoreActions.updateClusterNode(t.props.system, node)
      )
    }

    def dragMove(a: js.Any, b: Double): js.Any = {

      t.props.store.getSelectedCluster().foreach(cluster => {
        val mouse = d3.mouse(js.Dynamic.global.document.getElementById(b.toString))

        val newNodes: Seq[ClusterGraphNode] = t.state.nodes.map(n =>
          if (n.index == b) {
            js.Dynamic.literal(
              "host" -> n.host,
              "roles" -> n.roles,
              "status" -> n.status,
              "name" -> n.name,
              "index" -> b,
              "x" -> mouse(0),
              "y" -> mouse(1),
              "px" -> n.px,
              "py" -> n.py,
              "fixed" -> true,
              "weight" -> n.weight
            ).asInstanceOf[ClusterGraphNode]

          } else {
            n
          }
        )

        val newLinks: Seq[ClusterGraphLink] = getLinks(newNodes, t.props.mode, cluster)

        t.modState(s => s.copy(nodes = newNodes, links = newLinks))
      }
      )

      //      t.modState(s => s.copy(nodes = newNodes))

    }

  }

  val component = ReactComponentB[Props]("Graph")
    .initialStateP { P =>

      val force = d3.layout.force()
        .size(List[Double](P.width, P.height).toJsArray)
        .charge(-1500)
        .linkDistance(1000)
        //        .chargeDistance(1000)
        //        .linkDistance(500)
        .friction(0.9)

      val (nodes, links) = P.store.getSelectedCluster().map(cluster => {
        getNodesAndLink(cluster,
          P.mode,
          P.store.getDiscoveredClusterNodes().getOrElse(cluster.system, Nil),
          P.store.getSelectedDeps().getOrElse(cluster.system, Nil))
      }).getOrElse((Nil, Nil))

      State(nodes, links, force)

    }.backend(new Backend(_))
    .render((P, S, B) => {

      val selectedDeps = P.store.getSelectedDeps().getOrElse(P.system, Nil)

      val roles: Seq[(RoleDependency, Boolean)] =
        if (P.mode == Roles) {
          val alldeps = P.store.getSelectedCluster().map(_.dependencies).getOrElse(Nil)

          alldeps.map(eachDep => (eachDep, selectedDeps.exists(_.tpe.name == eachDep.tpe.name)))
        } else {
          Nil
        }

      svgtag(SvgAttrs.width := P.width, SvgAttrs.height := P.height)(
        drawDeps(roles, B.selectDep),
        drawLinks(S.links, P.mode),
        drawNodes(S.nodes, S.force, P.mode)
      )
    }).componentWillReceiveProps { (scope, P) =>
      val (nodes, links) = P.store.getSelectedCluster().map(cluster => {
        getNodesAndLink(cluster, P.mode,
          P.store.getDiscoveredClusterNodes().getOrElse(cluster.system, Nil),
          P.store.getSelectedDeps().getOrElse(cluster.system, Nil))
      }).getOrElse((Nil, Nil))

      val newState = State(nodes, links, scope.state.force)

      scope.modState { s =>
        val firstState = s.copy(nodes = nodes, links = links, force = s.force.nodes(nodes.toJsArray).start())
        firstState.copy(force = s.force.on("tick", () => scope.backend.renderTick))
      }

    }.componentWillMount { scope =>
      if (!scope.props.fixedMap) {
        scope.backend.startfixed()
      }

    }.componentDidMount { scope =>
      scope.backend.mounted()
      //
      //      val drag1 = d3.behavior.drag()
      //      drag1.origin(() => js.Array(0, 0)).on("drag", (a: js.Any, b: Double) => scope.backend.dragMove(a, b))
      //        .on("dragend", (a: js.Any, b: Double) => scope.backend.dragEnd(a, b))
      //      d3.select("svg").selectAll(".node").call(drag1)

    }.componentWillUnmount { scope =>
      scope.state.force.stop()
    }.configure(OnUnmount.install).build

  def apply(system: String, mode: Mode, width: Double, height: Double, store: ClusterStore, fixedMap: Boolean) = {

    component(Props(system, mode, width, height, store, fixedMap))
  }

  def getNodesAndLink(cluster: DiscoveredCluster,
    mode: Mode,
    fixedList: List[ClusterGraphNode], selectedDeps: List[RoleDependency]): (Seq[ClusterGraphNode], Seq[ClusterGraphLink]) = {
    val nodes =

      mode match {
        case Nodes =>
          val map = cluster.members.toSeq.groupBy(m => m.address.host)
          map.keys.zipWithIndex.flatMap {
            case (key, keyIndex) =>

              val newKeyIndex = 1000

              val ports: Seq[ClusterMember] = map.getOrElse[Seq[ClusterMember]](key, Seq.empty[ClusterMember])

              val portNodes: Seq[ClusterGraphNode] = ports.zipWithIndex.map {
                case (pNode, pIndex) =>
                  ClusterGraphNode.port(pNode, (newKeyIndex + 1 + pIndex), 450, 450, 450, 450, false, 1)
              }

              val hostNode: Option[ClusterGraphNode] =
                ports.headOption.map(port =>
                  ClusterGraphNode.host(port, newKeyIndex, 450, 450, 450, 450, false, ports.length))

              hostNode.fold(Seq.empty[ClusterGraphNode])(hn => hn +: portNodes)
          }

        case _ =>
          cluster.members.toSeq.zipWithIndex.map {
            case (node, i) =>
              fixedList.find(_.host == node.address.label).fold(
                ClusterGraphNode(node, i, 450, 450, 450, 450, false, 0)
              )(fixedNode => {
                  ClusterGraphNode(node,
                    fixedNode.index, fixedNode.x, fixedNode.y, fixedNode.px, fixedNode.py, fixedNode.fixed, 0)
                })
          }
      }
    (nodes.toSeq, getLinks(nodes.toSeq, mode, cluster, selectedDeps))
  }

  def getNewIndex(set: Set[Double], v: Double): Double =
    if (set.contains(v)) {
      getNewIndex(set, v + 1)
    } else {
      v
    }

  def getLinks(nodes: Seq[ClusterGraphNode], mode: Mode, cluster: DiscoveredCluster, roleDependencies: List[RoleDependency] = Nil) = {

    def makeLinks(conns: Seq[(Double, Double)]) =
      conns.flatMap {
        case (a, b) =>
          for {
            source <- nodes.find(_.index == a)
            target <- nodes.find(_.index == b)
          } yield {
            js.Dynamic.literal(
              "source" -> source,
              "target" -> target,
              "sourceHost" -> source.host,
              "targetHost" -> target.host).asInstanceOf[ClusterGraphLink]
          }
      }

    mode match {
      case Members =>
        val indexes = nodes.filter(_.status == "Up").map(_.index)
        makeLinks(indexes.flatMap(index => indexes.filter(_ > index).map((index, _))))
      case Roles =>

        log.debug("getLinks = " + roleDependencies)
        val allDeps = cluster.dependencies
        roleDependencies.zipWithIndex.flatMap {
          case (rd, index) =>

            log.debug("--------- roleDependencies " + (rd, index))

            val sourcesIndexes = rd.roles.flatMap { eachRole =>
              cluster.getNodesByRole(eachRole).toSeq.flatMap(e =>
                nodes.filter(n => ClusterGraphNode.label(n) == e.address.label).map(_.index))
            }

            val targetsIndexes = rd.dependsOn.flatMap { eachRole =>
              cluster.getNodesByRole(eachRole).toSeq.flatMap(e =>
                nodes.filter(n => ClusterGraphNode.label(n) == e.address.label).map(_.index))
            }

            val indexes = sourcesIndexes.flatMap(eachSource =>
              targetsIndexes.map(eachTarget =>
                (eachSource, eachTarget)))

            // get index of RoleDep

            indexes.flatMap {
              case (a, b) =>
                for {
                  source <- nodes.find(_.index == a)
                  target <- nodes.find(_.index == b)
                } yield {
                  js.Dynamic.literal(
                    "index" -> allDeps.indexOf(rd),
                    "source" -> source,
                    "target" -> target,
                    "sourceHost" -> source.host,
                    "targetHost" -> target.host).asInstanceOf[ClusterGraphRoleLink]
                }
            }

        }

      case Nodes =>

        //join ports to hosts
        val hostPortMap = nodes.groupBy(n => n.virtualHost)

        log.debug("hostPortMap " + hostPortMap)
        log.debug("hostPortMap " + hostPortMap.size)

        val indexes = hostPortMap.foldLeft[Seq[(Double, Double)]](Seq.empty[(Double, Double)])((a, b) => a ++ {

          log.debug("-- b._1 " + b._1)
          log.debug("-- b._2 " + b._2.map(p => p.port + " " + p.index))

          if (b._1.length > 0) {
            nodes.find(_.host == b._1).map(h =>
              b._2.map(e => (h.index, e.index))
            ).getOrElse(Nil)
          } else {
            Nil
          }
        }
        )
        makeLinks(indexes)
    }

  }

}


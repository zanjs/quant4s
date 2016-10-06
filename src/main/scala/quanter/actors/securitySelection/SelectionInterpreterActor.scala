/**
  *
  */
package quanter.actors.securitySelection

import akka.actor.{Actor, ActorLogging, Props}
import akka.routing.{RoundRobinPool, RoundRobinRouter}
import quanter.actors.zeromq.{PublishData, ZeroMQServerActor}
import quanter.rest.{FinanceIndi, SecurityPicker}
import quanter.securitySelection.{Instrument, Selector}

/**
  * 当接收到字符串的时候，进行分析 解释
  * PE GT 10 LT 15; 财务指标 送到 FinanceIndiActor 进行计算
  */
class SelectionInterpreterActor(cmds: SecurityPicker, topic: String, selector: Selector) extends Actor with ActorLogging{
  var indiCount = _countCmd
  var resultCount = 0
  var result: Selector = selector
  val financeIndiRef = context.actorSelection("/user")
  val pubRef = context.actorSelection("/user/" + ZeroMQServerActor.path)
  val finIndiRouter = context.actorOf(RoundRobinPool(5).props(Props.create(classOf[FinanceIndiActor],selector)))
  val secIndiRouter = context.actorOf(RoundRobinPool(5).props(Props.create(classOf[SectorIndiActor],selector)))

  _parse()

  override def receive: Receive = {
    case r: Selector => {
      // 接收到结果
      resultCount += 1
      result = result.intersect(r)

      if(resultCount == indiCount) {
        // 推送计算结果， 做order by
        if(cmds.orderName isDefined) {
          val cmpD:(Double, Double) => Int = cmds.orderBy.getOrElse("ASC") match {
            case "ASC" => (x: Double, y: Double) => x.compareTo(y)
            case "DESC" =>(x: Double, y: Double) => y.compareTo(x)
            case _ => (x: Double, y: Double) => x.compareTo(y)
          }
          val cmpL:(Long, Long) => Int = cmds.orderBy.getOrElse("ASC") match {
            case "ASC" => (x: Long, y: Long) => x.compareTo(y)
            case "DESC" =>(x: Long, y: Long) => y.compareTo(x)
            case _ => (x: Long, y: Long) => x.compareTo(y)
          }

          implicit val KeyOrdering = new Ordering[Instrument] {
            override def compare(x: Instrument, y: Instrument): Int = {
              cmds.orderName.get match {
                case "PE" => cmpD.apply(x.pe, y.pe)
                case "PB" => cmpD.apply(x.pb, y.pb)
                case "ROE" => cmpD.apply(x.roe, y.roe)
                case "MV" => cmpL.apply(x.mv, y.mv)
                case _ => cmpL.apply(x.mv, y.mv)
              }
            }
          }

          result.pool.sorted
        }

        var rs = ""
        result.pool.foreach( ins => rs = rs + ins.code + ",")
        if(rs .length > 0)
          rs = rs.substring(0, rs.length - 1)
        pubRef ! PublishData(topic, rs)
      }
    }
  }

  def _parse(): Unit = {
    for(cmd <- cmds.financeIndi) { // 采用 router， 提高并发性
      finIndiRouter ! cmd
    }

    if(cmds.sectorIndi.isDefined) {
      for(cmd <- cmds.sectorIndi.get) {
        secIndiRouter ! cmd
      }
    }

    if(cmds.techIndi.isDefined) {
      for(cmd <- cmds.techIndi.get) {
        // 技术指标选股
      }
    }
  }

  def _countCmd(): Int = {
    cmds.financeIndi.length + cmds.sectorIndi.getOrElse(List[String]()).length
  }
}

object SelectionInterpreterActor {
  def props(cmds: SecurityPicker, topic: String, selector: Selector) = {
    Props(classOf[SelectionInterpreterActor], cmds, topic, selector)
  }
}





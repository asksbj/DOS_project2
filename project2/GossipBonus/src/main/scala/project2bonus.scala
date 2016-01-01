import akka.actor.{Actor,ActorRef,ActorSystem,Props,actorRef2Scala,ActorContext,Cancellable}
import scala.collection.mutable.ArrayBuffer
import scala.math._
import akka.routing.RoundRobinRouter
import scala.util.Random
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import ExecutionContext.Implicits.global
import scala.concurrent.Future
import akka.pattern.ask

case class Gossip(nodeid:Int)
case object GossipReceive
case class Gossipfinish(nodeid:Int)
case class PushSum(nodeid:Int,s:Double,w:Double)
case object PushSumReceive
case class PushSumfinish(nodeid:Int)
case class Removenode(nodeid:Int)
case class NodeDead(nodeid:Int)
case object NodeFail
case object CheckNeighbors
case class NeighborsOnList(nodeid:Int, neighborlive:ArrayBuffer[Int], algorithm:String)
case class StopWork(algorithm: String)



object project2bonus extends App{
  override def main(args: Array[String]): Unit={
    var numNodes=args(0).toInt
    var topology=args(1)
    var algorithm=args(2)
    var errornodes:Int=args(3).toInt      //set the number of nodes will be killed in this model
    val message="This is a gossip"
    val system = ActorSystem("gossip")
    val master = system.actorOf(Props(new master(numNodes,topology,algorithm,errornodes)),name="master")
  }
}


object Topology {
  def setneighbors(nodeid:Int,numNodes:Int,topology:String) : ArrayBuffer[Int] = {
    var neighbors:ArrayBuffer[Int]=new ArrayBuffer[Int]()
    topology match { 
      case "full" =>
        for( n <- 0 to numNodes-1)
        {
         if(nodeid != n)
           neighbors+=n
        }
    
      case "line" =>
        if(nodeid>0)
          neighbors+=(nodeid-1)
        if(nodeid<numNodes-1)
          neighbors+=(nodeid+1)
      
      case "3D" =>
        var dim:Int=cbrt(numNodes).toInt
        if((nodeid%dim)!=0)
          neighbors+=(nodeid-1)
        if((nodeid%dim)!=(dim-1) && (nodeid != (numNodes-1)))
          neighbors+=(nodeid+1)
        if(nodeid%(dim*dim) >= dim)
          neighbors+=(nodeid-dim)
        if(((nodeid+dim)%(dim*dim) >= dim) && ((nodeid+dim) <= numNodes-1))
          neighbors+=(nodeid+dim)
        if(nodeid-dim*dim >= 0)
          neighbors+=(nodeid-dim*dim)
        if(nodeid+dim*dim <=numNodes-1)
          neighbors+=(nodeid+dim*dim)
        
        
      case "imp3D" =>
        var dim:Int=sqrt(numNodes).toInt
       
        if((nodeid%dim)!=0)
          neighbors+=(nodeid-1)
        if((nodeid%dim)!=(dim-1) && (nodeid != (numNodes-1)))
          neighbors+=(nodeid+1)
        if(nodeid%(dim*dim) >= dim)
          neighbors+=(nodeid-dim)
        if(((nodeid+dim)%(dim*dim) >= dim) && ((nodeid+dim) <= numNodes-1))
          neighbors+=(nodeid+dim)
        if(nodeid-dim*dim >= 0)
          neighbors+=(nodeid-dim*dim)
        if(nodeid+dim*dim <=numNodes-1)
          neighbors+=(nodeid+dim*dim)
        var rand:Int=Random.nextInt(numNodes)
        while((neighbors.contains(rand)) || (rand==nodeid))
          rand=Random.nextInt(numNodes)
        neighbors+=rand
        
    }
    return neighbors   
  } 
}

class master(numNodes: Int, topology: String, algorithm: String, errornodes: Int) extends Actor{
  var numFinish:Int=0
  var numWorkers:Int=0
  var node:List[ActorRef] = Nil
  var workerlist:ArrayBuffer[Int]=new ArrayBuffer[Int]()
  var messagelist:ArrayBuffer[Int]=new ArrayBuffer[Int]()
  var errornode:Int=errornodes
  var nodestop:Int=0
  
  val system = ActorSystem("Node")
  numWorkers=numNodes
  for(i<-0 to numWorkers-1){
      var randmsg=Random.nextInt(numWorkers)
      while(messagelist.contains(randmsg))
        randmsg=Random.nextInt(numWorkers)
      messagelist+=randmsg
           
    } 
    for(i<-0 to numWorkers-1){ 
      workerlist+=(i)
      context.actorOf(Props(new worker(i,numNodes,topology,algorithm,messagelist(i))),i.toString())
    } 
	val b=System.currentTimeMillis
    algorithm match {
      case "gossip" =>
        println("start gossip")
        context.actorSelection("0") ! Gossip(0)
      case "push-sum" =>
        println("start push-sum")
        context.actorSelection("0") ! PushSum(0,0,0)
      case _ =>
        printf("Please enter 'gossip' or 'push-sum' for algorithm")
        context.stop(self)
        context.system.shutdown()
        System.exit(0)
    } 
 // }
  
  def checklive = {  //get the neighbor of the node
    for(i<- 0 to workerlist.length-1)
      context.actorSelection(workerlist(i).toString()) ! CheckNeighbors
  }
  
  def receive = {
    
    case NeighborsOnList(nodeid,neighborlive,algorithm) => {   //receive the neighbor list of node, if all element the neighbor list are dead nodes, stop the node as well
     if(workerlist.contains(nodeid)&& neighborlive.length!=0){
         var nodestopflag=false
        for(i<-0 to neighborlive.length-1){
        if(workerlist.contains(neighborlive(i))){
          nodestopflag=true
        }
        }
        if(nodestopflag==false){
          println(nodeid+" only have neighbor already dead")
          context.actorSelection(nodeid.toString()) ! StopWork(algorithm)
          workerlist-=nodeid
           nodestop+=1
        }
        else{
          nodestopflag=false
        }
          
     }
      
    }

    case Gossipfinish(nodeid) => {
      numFinish+=1
      //println("finished count"+finished_count+ " worker no "+workernumber)
      //println("id of worker finished:"+nodeid)
      //println("number of worker have finished: "+numFinish)
      workerlist -= nodeid
      if(workerlist.length>0 && errornode>0){  //after the first node finished, the system begin kill nodes
        var rand=Random.nextInt(workerlist.length)
        context.actorSelection(workerlist(rand).toString()) ! NodeFail
        errornode -=1
        workerlist -=workerlist(rand)
      }
      if(workerlist.length>0 && workerlist.length<=2*errornodes){     //begin checking process when the number of nodes still on working is less than twice the error nodes
        println("Almost finished: Number of nodes still on working is "+workerlist.length)
        println("Time taken "+(System.currentTimeMillis-b))
        checklive
      }
      if(workerlist.length==0 && (numFinish>=(numWorkers-errornodes))){ 
        println("All worker finished work")
        //println("The number of nodes finished work is "+numFinish)
         println("Number of nodes never stop "+nodestop)
        println("Time taken "+(System.currentTimeMillis-b))   
        context.stop(self)
        context.system.shutdown
        System.exit(0)   
      }
        
      
    }
    

    
    case PushSumfinish(nodeid) => {
      numFinish+=1
      //println("finished count"+finished_count+ " worker no "+workernumber)
      //println("id of worker finished:"+nodeid)
      //println("number of worker have finished: "+numFinish)
      workerlist -= nodeid
      if(workerlist.length>0 && errornode>0){
        var rand=Random.nextInt(workerlist.length)
        context.actorSelection(workerlist(rand).toString()) ! NodeFail
        errornode -=1
        workerlist -=workerlist(rand)
      }
      if(workerlist.length>0 && workerlist.length<=2*errornodes){
        println("Almost finished: Number of nodes still on working is "+workerlist.length)
        println("Time taken "+(System.currentTimeMillis-b))
        checklive
      }
      if(workerlist.length==0 && (numFinish>=(numWorkers-errornodes))){
        println("All worker finished work")
        //println("The number of nodes finished work is "+numFinish)
         println("Number of nodes nerver stop "+nodestop)
        println("Time taken "+(System.currentTimeMillis-b))   
        context.stop(self)
        context.system.shutdown
        System.exit(0)   
      }        
    }
  }    
}

class worker(nodeid: Int, numNodes: Int, topology: String, algorithm: String, message: Int) extends Actor {
   var rumorreceived=0 
   var gossipflag=false
   var pushsumflag=false
   var pushsumcount:Int=0
   var neighbors=ArrayBuffer[Int]()
   var sendnumber: Int=0
   val system = ActorSystem("Node")
   var s:Double = nodeid+1
   var w:Double = 1
   neighbors=Topology.setneighbors(nodeid,numNodes,topology)

   def receive = {
     case Gossip(nodeid) => {
       if(gossipflag==false){
       rumorreceived+=1
        
         if(neighbors.length>0 && rumorreceived >= 10){
           //println(nodeid+" has received 10 rumors")
           for(i<-0 to neighbors.length-1)
             context.actorSelection("../"+neighbors(i)) ! Removenode(nodeid)
           context.parent ! Gossipfinish(nodeid)
           gossipflag=true
           //context.stop(self)
         }
         else{
           if(neighbors.length>0 && rumorreceived <10)
             self ! GossipReceive
         }
       }
       else{
         sender ! NodeDead(nodeid)
       }
     }
     
     case GossipReceive => {
       if(gossipflag==false){
       if(rumorreceived < 10 && neighbors.length > 0){
         var rand=Random.nextInt(neighbors.length)
         sendnumber+=1
         //println(nodeid+" has send "+sendnumber+" messages")
         context.actorSelection("../"+neighbors(rand).toString()) ! Gossip(neighbors(rand))
         context.system.scheduler.scheduleOnce(200 milliseconds, self, GossipReceive)
       }
       if(neighbors.length == 0){
         context.parent ! Gossipfinish(nodeid)
         gossipflag=true
         //context.stop(self)
       }
       }
     }
     
     
     case PushSum(nodeid,s,w) => {
       if(pushsumflag==false){
        // println(nodeid+" receive message")
       if (Math.abs((this.s/this.w) - ((this.s + s)/(this.w + w))) <= 1E-10)
         pushsumcount+= 1
       else
         pushsumcount = 0
       this.s+=s
       this.w+=w
      

         if(neighbors.length>0 && pushsumcount == 3){
           //println(nodeid+" has converged")
           for(i<-0 to neighbors.length-1)
             context.actorSelection("../"+neighbors(i)) ! Removenode(nodeid)
           context.parent ! PushSumfinish(nodeid)
           pushsumflag=true
           context.stop(self) 
         }
         else{
           if(neighbors.length>0 && pushsumcount < 3)
             self ! PushSumReceive
         }
       }
       else{
         sender ! NodeDead(nodeid)
       }
     } 
     
     case PushSumReceive => {
       if(pushsumflag==false){
       if(pushsumcount < 3 && neighbors.length > 0){
         this.s/=2
         this.w/=2
         var rand=Random.nextInt(neighbors.length)
         context.actorSelection("../"+neighbors(rand).toString()) ! PushSum(neighbors(rand),this.s,this.w)
         context.system.scheduler.scheduleOnce(200 milliseconds, self, PushSumReceive)
       }
       if(neighbors.length == 0){
         //println(nodeid+" has no neighbors")
         context.parent ! PushSumfinish(nodeid)
         pushsumflag=true
       }
       }
     }
     
     case Removenode(nodeid) =>
       neighbors -= nodeid
       
     case NodeDead(nodeid) => 
       neighbors -= nodeid
     
     case NodeFail =>  //the node is killed by the system, it shut down and does nothing any more
       println(nodeid+" failed")
       context.stop(self)
       
     case CheckNeighbors =>
       sender ! NeighborsOnList(nodeid,neighbors,algorithm)
       
     case StopWork(algorithm) => { //the node has only error nodes in its neighbor list, it is forced to stop after the checking process
       algorithm match  {
         case "gossip" =>
           context.parent ! Gossipfinish(nodeid)
           context.stop(self)
         case "push-sum" =>
           context.parent ! PushSumfinish(nodeid)
           context.stop(self)
       }

     }
       

   }
 } 


   





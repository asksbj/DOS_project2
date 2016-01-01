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


object project2 extends App{
  override def main(args: Array[String]): Unit={
    var numNodes=args(0).toInt
    var topology=args(1)
    var algorithm=args(2)
    //val message="This is a gossip"
    val system = ActorSystem("gossip")
    val master = system.actorOf(Props(new master(numNodes,topology,algorithm)),name="master")
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
      
      case "3D" =>  //cubic 3D model
        var dim:Int=cbrt(numNodes).toInt
        if((nodeid%dim)!=0)    //node on the left
          neighbors+=(nodeid-1)
        if((nodeid%dim)!=(dim-1) && (nodeid != (numNodes-1)))  //node on the right
          neighbors+=(nodeid+1)
        if(nodeid%(dim*dim) >= dim)  //node above
          neighbors+=(nodeid-dim)
        if(((nodeid+dim)%(dim*dim) >= dim) && ((nodeid+dim) <= numNodes-1))  //node under
          neighbors+=(nodeid+dim)
        if(nodeid-dim*dim >= 0)  //node in front 
          neighbors+=(nodeid-dim*dim)
        if(nodeid+dim*dim <=numNodes-1)  //node at the back
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


class master(numNodes: Int, topology: String, algorithm: String) extends Actor{
  var numFinish:Integer=0
  
  var numWorkers=0
  var node:List[ActorRef] = Nil
  var workerlist:ArrayBuffer[Int]=new ArrayBuffer[Int]()
  var messagelist:ArrayBuffer[Int]=new ArrayBuffer[Int]()
    val system = ActorSystem("Node")
    numWorkers=numNodes
    for(i<-0 to numWorkers-1){        //set number of s for each node in push-sum algorithm, the value is chosen randomly
      var randmsg=Random.nextInt(numWorkers)
      while(messagelist.contains(randmsg))
        randmsg=Random.nextInt(numWorkers)
      messagelist+=randmsg
           
    }  
    for(i<-0 to numWorkers-1){    //save the nodeid of nodes on working
      workerlist+=i
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
  
  def receive = {

    case Gossipfinish(nodeid) => {
      numFinish+=1
      //println("finished count"+finished_count+ " worker no "+workernumber)
      //println("id of worker finished:"+nodeid)
      //println("number of worker have finished: "+numFinish)
      workerlist -= nodeid
      if(workerlist.length==0){
        println("All worker finished work")
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
      if(workerlist.length==0)
        println("All worker finished work")
      
      if(numFinish==numWorkers){
        println("Time taken "+(System.currentTimeMillis-b))   
      context.stop(self)
      context.system.shutdown
      System.exit(0)
	   //println("All worker finished work")
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
   var s:Double = message+1
   //println(s)
   var w:Double = 1
   neighbors=Topology.setneighbors(nodeid,numNodes,topology)   //choose neighbors according to topology

   def receive = {  
     case Gossip(nodeid) => { //receive a message, check if the converge requirement is met
       if(gossipflag==false){
       rumorreceived+=1
        
         if(neighbors.length>0 && rumorreceived >= 10){
           //println(nodeid+" has received 10 rumors")
           /*if(topology == "imp3D"){
             for(i<-0 to numNodes-1){
               if(nodeid!=i)
                context.actorSelection("../"+i.toString()) ! Removenode(nodeid)
             } 
           }
           else{
             for(i<-0 to neighbors.length-1)
               context.actorSelection("../"+neighbors(i).toString()) ! Removenode(nodeid)
           }*/
           for(i<-0 to neighbors.length-1)
               context.actorSelection("../"+neighbors(i).toString()) ! Removenode(nodeid)
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
     
     case GossipReceive => {   //after received a message, send message to one of its neighbor node randomly every 200ms
       if(gossipflag==false){
       if(rumorreceived < 10 && neighbors.length > 0){
         var rand=Random.nextInt(neighbors.length)
         sendnumber+=1
         //println(nodeid+" has send "+sendnumber+" messages")
         context.actorSelection("../"+neighbors(rand).toString()) ! Gossip(neighbors(rand))
         context.system.scheduler.scheduleOnce(200 milliseconds, self, GossipReceive)
       }
       if(neighbors.length == 0){
         context.parent ! Gossipfinish(nodeid);
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
       //this.s/=2
       //this.w/=2

         if(neighbors.length>0 && pushsumcount == 3){
           //println(nodeid+" has converged")
           for(i<-0 to neighbors.length-1)
             context.actorSelection("../"+neighbors(i).toString()) ! Removenode(nodeid)
           context.parent ! PushSumfinish(nodeid)
           pushsumflag=true
           //println(nodeid,s/w)
           //context.stop(self) 
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
         context.system.scheduler.scheduleOnce(500 milliseconds, self, PushSumReceive)
       }
       if(neighbors.length == 0){
         //println(nodeid+" has no neighbors")
         context.parent ! PushSumfinish(nodeid);
         pushsumflag=true
       }
       }
     }
     
     case Removenode(nodeid) =>
       neighbors -= nodeid
       
     case NodeDead(nodeid)=> 
       neighbors -= nodeid
     
   }
 } 


   





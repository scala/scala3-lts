object Test {

  sealed abstract class InPort { self: Inlet[_] =>
    final override def hashCode: Int = super.hashCode
  }

  sealed abstract class OutPort { self: Outlet[_] =>
    final override def hashCode: Int = super.hashCode
  }

  final class Inlet[T](val s: String) extends InPort {
    override def toString: String =
      s + "(" + this.hashCode + ")"
  }

  final class Outlet[T](val s: String) extends OutPort {
    override def toString: String =
      s + "(" + this.hashCode + ")"
  }
  def main(args: Array[String]): Unit = {
    val myPort = Outlet[Int]("myPort")
    println(myPort.hashCode)
  }
}
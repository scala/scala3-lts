enum TrafficLight:
  case Green, Yellow, Red

object Test:
  def main(args: Array[String]): Unit = println(Macro.make[TrafficLight])

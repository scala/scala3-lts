// https://github.com/scala/scala3/issues/27009
import scala.jdk.CollectionConverters._
import scala.reflect.Selectable.reflectiveSelectable

object Issue {

  private type RowWriter = {
    def setMandatory(mandatory: Array[String]): Unit
    def setTotal(total: java.lang.Long): Unit
  }

  private type BulkWriter = {
    def setMandatory(mandatory: java.util.List[Array[String]]): Unit
    def setTotal(total: java.util.List[java.lang.Long]): Unit
  }

  private def applyMandatoryDocSubtypeDataWhenAligned(bulkWriter: BulkWriter,
                                                      mandatoryDocRows: Iterable[RowWriter],
                                                      chronicleIds: List[String],
                                                      subtypes: List[Array[String]],
                                                      totals: List[java.lang.Long]
                                                     ): Unit =
    if (mandatoryDocRows.sizeCompare(chronicleIds.size) == 0) {
      applyMandatory(mandatoryDocRows, subtypes, totals)
    } else {
      bulkWriter.setMandatory(subtypes.asJava)
    }

  private def applyMandatory(
                                                  rows: Iterable[RowWriter],
                                                  subtypes: List[Array[String]],
                                                  totals: List[java.lang.Long]
                                                ): Unit =
    rows.zipWithIndex.foreach { case (row, index) =>
      if (index < subtypes.size && index < totals.size) {
        row.setMandatory(cloneSubtypeArray(subtypes(index)))
        row.setTotal(totals(index))
      }
    }

  private def cloneSubtypeArray(subtypes: Array[String]): Array[String] =
    if (subtypes == null) Array.empty else subtypes.clone()
}

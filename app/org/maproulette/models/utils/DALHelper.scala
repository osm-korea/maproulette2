// Copyright (C) 2016 MapRoulette contributors (see CONTRIBUTORS.md).
// Licensed under the Apache License, Version 2.0 (see LICENSE).
package org.maproulette.models.utils

import java.sql.SQLException
import java.text.SimpleDateFormat
import java.util.{Calendar, Date}

import anorm._
import org.apache.commons.lang3.StringUtils
import org.joda.time.DateTime
import org.joda.time.format.DateTimeFormat
import org.maproulette.data.DataManager

import scala.collection.mutable.ListBuffer

sealed trait SQLKey {
  def getSQLKey() : String
}

case class AND() extends SQLKey { override def getSQLKey() : String = "AND" }
case class OR() extends SQLKey { override def getSQLKey() : String = "OR" }
case class WHERE() extends SQLKey { override def getSQLKey(): String = "WHERE" }

/**
  * Helper functions for any Data Access Layer classes
  *
  * @author cuthbertm
  */
trait DALHelper {
  private val dateFormat = DateTimeFormat.forPattern("yyyy-MM-dd")
  // The set of characters that are allowed for column names, so that we can sanitize in unknown input
  // for protection against SQL injection
  private val ordinary = (('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9') ++ Seq('_') ++ Seq('.')).toSet

  /**
    * Function will return "ALL" if value is 0 otherwise the value itself. Postgres does not allow
    * using 0 for ALL
    *
    * @param value The limit used in the query
    * @return ALL if 0 otherwise the value
    */
  def sqlLimit(value:Int) : String = if (value <= 0) "ALL" else value + ""

  /**
    * All MapRoulette objects contain the enabled column that define whether it is enabled in the
    * system or not. This will create the WHERE part of the clause checking for enabled values in the
    * query
    *
    * @param value If looking only for enabled elements this needs to be set to true
    * @param tablePrefix If used as part of a join or simply the table alias if required
    * @param key Defaulted to "AND"
    * @return
    */
  def enabled(value:Boolean, tablePrefix:String="")
             (implicit key:Option[SQLKey]=Some(AND())) : String = {
    if (value) {
      s"${this.getSqlKey} ${this.getPrefix(tablePrefix)}enabled = TRUE"
    } else {
      ""
    }
  }

  /**
    * This function will handle the conjunction in a where clause. So if you are you creating
    * a dynamic where clause this will handle adding the conjunction clause if required
    *
    * @param whereClause The StringBuilder where clause
    * @param value The value that is being appended
    * @param conjunction The conjunction, by default AND
    */
  def appendInWhereClause(whereClause:StringBuilder, value:String)
                         (implicit conjunction:Option[SQLKey]=Some(AND())) : Unit = {
    if (whereClause.nonEmpty && value.nonEmpty) {
      whereClause ++= s"${this.getSqlKey} $value"
    } else {
      whereClause ++= value
    }
  }

  /**
    * Set the search field in the where clause correctly, it will also surround the values
    * with LOWER to make sure that match is case insensitive
    *
    * @param column The column that you are searching against
    * @param conjunction Default is AND, but can use AND or OR
    * @param key The search string that you are testing against
    * @return
    */
  def searchField(column:String, key:String="ss")
                 (implicit conjunction:Option[SQLKey]=Some(AND())) : String =
    s"${this.getSqlKey} LOWER($column) LIKE LOWER({$key})"

  /**
    * Corrects the search string by adding % before and after string, so that it doesn't rely
    * on simply an exact match. If value not supplied, then will simply return %
    *
    * @param value The search string that you are using to match with
    * @return
    */
  def search(value:String) : String = if (value.nonEmpty) s"%$value%" else "%"

  /**
    * Creates the ORDER functionality, with the column and direction
    *
    * @param orderColumn The column that you are ordering with (or multiple comma separated columns)
    * @param orderDirection Direction of ordering ASC or DESC
    * @param tablePrefix table alias if required
    * @param nameFix The namefix really is just a way to force certain queries specific to MapRoulette
    *                to use a much more efficient query plan. The difference in performance can be quite
    *                large. We don't do it by default because it relies on the "name" column which is
    *                not guaranteed.
    * @return
    */
  def order(orderColumn:Option[String]=None, orderDirection:String="ASC", tablePrefix:String="",
            nameFix:Boolean=false) : String = orderColumn match {
    case Some(column) =>
      this.testColumnName(column)
      val direction = orderDirection match {
        case "DESC" => "DESC"
        case _ => "ASC"
      }
      // sanitize the column name to prevent sql injection. Only allow underscores and A-Za-z
      if (column.forall(this.ordinary.contains)) {
        s"ORDER BY ${this.getPrefix(tablePrefix)}$column ${if (nameFix) {"," + this.getPrefix(tablePrefix) + "name";} else {"";}} $direction"
      } else {
        ""
      }
    case None => ""
  }

  def sqlWithParameters(query:String, parameters:ListBuffer[NamedParameter]) : SimpleSql[Row] = {
    if (parameters.nonEmpty) {
      SQL(query).on(parameters:_*)
    } else {
      SQL(query).asSimple[Row]()
    }
  }

  def parentFilter(parentId:Long)
                  (implicit conjunction:Option[SQLKey]=Some(AND())) : String = if (parentId != -1) {
    s"${this.getSqlKey} parent_id = $parentId"
  } else {
    ""
  }

  def getLongListFilter(list:Option[List[Long]], columnName:String)
                       (implicit conjunction:Option[SQLKey]=Some(AND())) : String = {
    this.testColumnName(columnName)
    list match {
      case Some(idList) if idList.nonEmpty =>
        s"${this.getSqlKey} $columnName IN (${idList.mkString(",")})"
      case _ => ""
    }
  }

  def getIntListFilter(list:Option[List[Int]], columnName:String)
                      (implicit conjunction:Option[SQLKey]=Some(AND())) : String = {
    this.testColumnName(columnName)
    list match {
      case Some(idList) if idList.nonEmpty =>
        s"${this.getSqlKey} $columnName IN (${idList.mkString(",")})"
      case _ => ""
    }
  }

  def getDates(start:Option[DateTime]=None, end:Option[DateTime]=None) : (String, String) = {
    val startDate = start match {
      case Some(s) => dateFormat.print(s)
      case None => dateFormat.print(DateTime.now().minusWeeks(1))
    }
    val endDate = end match {
      case Some(e) => dateFormat.print(e)
      case None => dateFormat.print(DateTime.now())
    }
    (startDate, endDate)
  }

  def getDateClause(column:String, start:Option[DateTime]=None, end:Option[DateTime]=None)
                   (implicit sqlKey:Option[SQLKey]=None) : String = {
    this.testColumnName(column)
    val dates = getDates(start, end)
    s"${this.getSqlKey} $column::date BETWEEN '${dates._1}' AND '${dates._2}'"
  }

  /**
    * Just appends the period at the end of the table prefix if the provided string is not empty
    *
    * @param prefix The table prefix that is being used in the query
    * @return
    */
  private def getPrefix(prefix:String) : String =
    if (StringUtils.isEmpty(prefix) || !prefix.forall(this.ordinary.contains)) "" else s"$prefix."

  private def testColumnName(columnName:String) : Unit = {
    if (!columnName.forall(this.ordinary.contains)) {
      throw new SQLException(s"Invalid column name provided `$columnName`")
    }
  }

  private def getSqlKey(implicit conjunction:Option[SQLKey]) : String = {
    conjunction match {
      case Some(c) => c.getSQLKey()
      case None => ""
    }
  }
}

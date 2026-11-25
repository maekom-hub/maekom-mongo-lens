package com.maekom.mongolens.ui

import com.maekom.mongolens.i18n.I18n

data class ShellTemplate(
    val id: String,
    val titleKey: String,
    val isWriteOp: Boolean = false,
    private val templateBody: String
) {
    fun render(collectionName: String): String = templateBody.replace("<col>", collectionName)
}

data class ShellTemplateCategory(
    val titleKey: String,
    val templates: List<ShellTemplate>
)

object MongoShellTemplates {

    fun defaultStarter(collectionName: String): String =
        """db.$collectionName.find({}).sort({ "_id": -1 }).limit(20)"""

    fun categories(): List<ShellTemplateCategory> = listOf(
        // 1. 기본 조회 & 정렬 (4종)
        ShellTemplateCategory(
            titleKey = "shell.tpl.cat.basic",
            templates = listOf(
                ShellTemplate(
                    id = "basic_find_sort_limit",
                    titleKey = "shell.tpl.basic.findSortLimit",
                    templateBody = """db.<col>.find({ "status": "ACTIVE" })
  .sort({ "_id": -1 })
  .skip(0)
  .limit(20)"""
                ),
                ShellTemplate(
                    id = "basic_projection",
                    titleKey = "shell.tpl.basic.projection",
                    templateBody = """db.<col>.find(
  { "status": "ACTIVE" },
  { "_id": 1, "name": 1, "createdAt": 1 }
).limit(20)"""
                ),
                ShellTemplate(
                    id = "basic_count",
                    titleKey = "shell.tpl.basic.count",
                    templateBody = """db.<col>.countDocuments({ "status": "ACTIVE" })"""
                ),
                ShellTemplate(
                    id = "basic_distinct",
                    titleKey = "shell.tpl.basic.distinct",
                    templateBody = """db.<col>.distinct("status", {})"""
                )
            )
        ),

        // 2. 비교/날짜/검색 연산자 (4종)
        ShellTemplateCategory(
            titleKey = "shell.tpl.cat.operators",
            templates = listOf(
                ShellTemplate(
                    id = "op_in",
                    titleKey = "shell.tpl.op.in",
                    templateBody = """db.<col>.find({
  "_id": {
    "${'$'}in": [
      ObjectId("000000000000000000000001"),
      ObjectId("000000000000000000000002")
    ]
  }
})"""
                ),
                ShellTemplate(
                    id = "op_date_range",
                    titleKey = "shell.tpl.op.dateRange",
                    templateBody = """db.<col>.find({
  "createdAt": {
    "${'$'}gte": ISODate("2026-01-01T00:00:00.000Z"),
    "${'$'}lte": {{${'$'}now}}
  }
}).sort({ "createdAt": -1 }).limit(50)"""
                ),
                ShellTemplate(
                    id = "op_regex",
                    titleKey = "shell.tpl.op.regex",
                    templateBody = """db.<col>.find({
  "name": { "${'$'}regex": "keyword", "${'$'}options": "i" }
}).limit(20)"""
                ),
                ShellTemplate(
                    id = "op_exists_null",
                    titleKey = "shell.tpl.op.existsNull",
                    templateBody = """db.<col>.find({
  "deletedAt": { "${'$'}exists": true, "${'$'}ne": null }
}).limit(20)"""
                )
            )
        ),

        // 3. 집계 파이프라인 (3종)
        ShellTemplateCategory(
            titleKey = "shell.tpl.cat.agg",
            templates = listOf(
                ShellTemplate(
                    id = "agg_group_sum",
                    titleKey = "shell.tpl.agg.groupSum",
                    templateBody = """db.<col>.aggregate([
  { "${'$'}match": { "status": { "${'$'}ne": "CANCELLED" } } },
  {
    "${'$'}group": {
      "_id": "${'$'}status",
      "count": { "${'$'}sum": 1 },
      "totalAmount": { "${'$'}sum": "${'$'}amount" }
    }
  },
  { "${'$'}sort": { "count": -1 } }
])"""
                ),
                ShellTemplate(
                    id = "agg_unwind",
                    titleKey = "shell.tpl.agg.unwind",
                    templateBody = """db.<col>.aggregate([
  { "${'$'}unwind": "${'$'}items" },
  { "${'$'}limit": 20 }
])"""
                ),
                ShellTemplate(
                    id = "agg_lookup",
                    titleKey = "shell.tpl.agg.lookup",
                    templateBody = """db.<col>.aggregate([
  {
    "${'$'}lookup": {
      "from": "targetCollection",
      "localField": "targetId",
      "foreignField": "_id",
      "as": "joinedDocs"
    }
  },
  { "${'$'}limit": 20 }
])"""
                )
            )
        ),

        // 4. 데이터 수정/삭제 (개발 환경 전용 - PRD 자동 차단, 3종)
        ShellTemplateCategory(
            titleKey = "shell.tpl.cat.write",
            templates = listOf(
                ShellTemplate(
                    id = "write_update_one",
                    titleKey = "shell.tpl.write.updateOne",
                    isWriteOp = true,
                    templateBody = """db.<col>.updateOne(
  { "_id": ObjectId("000000000000000000000001") },
  { "${'$'}set": { "status": "UPDATED", "updatedAt": {{${'$'}now}} } }
)"""
                ),
                ShellTemplate(
                    id = "write_update_many",
                    titleKey = "shell.tpl.write.updateMany",
                    isWriteOp = true,
                    templateBody = """db.<col>.updateMany(
  { "status": "PENDING" },
  { "${'$'}set": { "status": "PROCESSED", "updatedAt": {{${'$'}now}} } }
)"""
                ),
                ShellTemplate(
                    id = "write_delete_one",
                    titleKey = "shell.tpl.write.deleteOne",
                    isWriteOp = true,
                    templateBody = """db.<col>.deleteOne({ "_id": ObjectId("000000000000000000000001") })"""
                )
            )
        )
    )

    fun isDefaultOrBlank(currentText: String, collectionName: String): Boolean {
        val trimmed = currentText.trim()
        return trimmed.isEmpty() || trimmed == defaultStarter(collectionName).trim()
    }

    fun titleOf(tpl: ShellTemplate): String = I18n.t(tpl.titleKey)
}

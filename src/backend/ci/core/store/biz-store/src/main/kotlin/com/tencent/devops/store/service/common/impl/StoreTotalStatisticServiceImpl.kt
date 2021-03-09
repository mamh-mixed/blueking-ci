/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.store.service.common.impl

import com.tencent.devops.common.api.constant.FAIL_NUM
import com.tencent.devops.common.api.constant.NAME
import com.tencent.devops.common.api.util.DateTimeUtil
import com.tencent.devops.common.redis.RedisLock
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.store.dao.common.StoreStatisticDao
import com.tencent.devops.store.dao.common.StoreStatisticTotalDao
import com.tencent.devops.store.pojo.common.StoreStatistic
import com.tencent.devops.store.pojo.common.StoreStatisticPipelineNumUpdate
import com.tencent.devops.store.pojo.common.StoreStatisticTrendData
import com.tencent.devops.store.pojo.common.enums.StoreTypeEnum
import com.tencent.devops.store.service.common.StoreDailyStatisticService
import com.tencent.devops.store.service.common.StoreTotalStatisticService
import org.jooq.DSLContext
import org.jooq.Record4
import org.jooq.Record5
import org.jooq.Result
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.Calendar

@Suppress("ALL")
@Service
class StoreTotalStatisticServiceImpl @Autowired constructor(
    private val dslContext: DSLContext,
    private val redisOperation: RedisOperation,
    private val storeStatisticDao: StoreStatisticDao,
    private val storeStatisticTotalDao: StoreStatisticTotalDao,
    private val storeDailyStatisticService: StoreDailyStatisticService
) : StoreTotalStatisticService {
    companion object {
        private val logger = LoggerFactory.getLogger(StoreTotalStatisticServiceImpl::class.java)
        private const val DEFAULT_PAGE_SIZE = 50
    }

    @Scheduled(cron = "0 0 * * * ?") // 每小时执行一次
    fun stat() {
        val lock = RedisLock(redisOperation, "storeTotalStatistic", 60000L)
        try {
            if (!lock.tryLock()) {
                logger.info("get lock failed, skip")
                return
            }
            val taskName = "StoreTotalStatisticTask"
            logger.info("$taskName:stat:start")
            StoreTypeEnum.values().forEach { storeType ->
                var offset = 0
                do {
                    val statistics = storeStatisticDao.batchGetStatisticByStoreCode(
                        dslContext = dslContext,
                        storeCodeList = listOf(),
                        storeType = storeType.type.toByte(),
                        offset = offset,
                        limit = DEFAULT_PAGE_SIZE
                    )
                    calculateAndStorage(
                        storeType = storeType.type.toByte(),
                        statistics = statistics
                    )
                    offset += DEFAULT_PAGE_SIZE
                } while (statistics.size == DEFAULT_PAGE_SIZE)
            }
            logger.info("$taskName:stat:end")
        } catch (ignored: Throwable) {
            logger.warn("storeTotalStatistic failed", ignored)
        } finally {
            lock.unlock()
        }
    }

    override fun updateStoreTotalStatisticByCode(storeCode: String, storeType: Byte) {
        calculateAndStorage(
            storeType = storeType,
            statistics = storeStatisticDao.batchGetStatisticByStoreCode(
                dslContext = dslContext,
                storeCodeList = listOf(storeCode),
                storeType = storeType
            )
        )
    }

    override fun getStatisticByCode(
        userId: String,
        storeType: Byte,
        storeCode: String
    ): StoreStatistic {
        val record =
            storeStatisticTotalDao.getStatisticByStoreCode(dslContext, storeCode, storeType)
        // 统计基于昨天为截止日期的最近三个月的数据的组件执行成功率
        val endTime = DateTimeUtil.convertDateToFormatLocalDateTime(
            date = DateTimeUtil.getFutureDateFromNow(Calendar.DAY_OF_MONTH, -1),
            format = "yyyy-MM-dd"
        )
        val dailyStatisticList = storeDailyStatisticService.getDailyStatisticListByCode(
            userId = userId,
            storeCode = storeCode,
            storeType = storeType,
            startTime = DateTimeUtil.convertDateToLocalDateTime(
                DateTimeUtil.getFutureDate(
                    localDateTime = endTime,
                    unit = Calendar.MONTH,
                    timeSpan = -3
                )
            ),
            endTime = endTime
        )
        var successRate: Double? = null
        if (dailyStatisticList != null) {
            var totalSuccessNum = 0
            var totalFailNum = 0
            dailyStatisticList.forEach { dailyStatistic ->
                totalSuccessNum += dailyStatistic.dailySuccessNum
                totalFailNum += dailyStatistic.dailyFailNum
            }
            successRate =
                String.format("%.2f", totalSuccessNum.toDouble() * 100 / totalSuccessNum + totalFailNum).toDouble()
        }
        return generateStoreStatistic(record, successRate)
    }

    override fun getStatisticByCodeList(
        storeType: Byte,
        storeCodeList: List<String>
    ): HashMap<String, StoreStatistic> {
        val records = storeStatisticTotalDao.batchGetStatisticByStoreCode(
            dslContext = dslContext,
            storeCodeList = storeCodeList,
            storeType = storeType
        )
        val storeStatisticMap = hashMapOf<String, StoreStatistic>()
        records?.map {
            if (it.value5() != null) {
                val storeCode = it.value5()
                storeStatisticMap[storeCode] = generateStoreStatistic(it)
            }
        }
        return storeStatisticMap
    }

    override fun updatePipelineNum(pipelineNumUpdateList: List<StoreStatisticPipelineNumUpdate>, storeType: Byte) {
        dslContext.transaction { t ->
            val context = DSL.using(t)
            storeStatisticTotalDao.batchUpdatePipelineNum(
                dslContext = context,
                pipelineNumUpdateList = pipelineNumUpdateList,
                storeType = storeType
            )
        }
    }

    override fun getStatisticTrendDataByCode(
        userId: String,
        storeType: Byte,
        storeCode: String,
        startTime: String,
        endTime: String
    ): StoreStatisticTrendData {
        logger.info("getStatisticTrendDataByCode $userId,$storeCode,$storeType,$startTime,$endTime")
        val dailyStatisticList = storeDailyStatisticService.getDailyStatisticListByCode(
            userId = userId,
            storeCode = storeCode,
            storeType = storeType,
            startTime = DateTimeUtil.stringToLocalDateTime(startTime),
            endTime = DateTimeUtil.stringToLocalDateTime(endTime)
        )
        var totalFailNum = 0
        var totalSystemFailNum = 0
        var totalUserFailNum = 0
        var totalThirdFailNum = 0
        var totalComponentFailNum = 0
        dailyStatisticList?.forEach { dailyStatistic ->
            totalFailNum += dailyStatistic.dailyFailNum
            val dailyFailDetail = dailyStatistic.dailyFailDetail
            if (dailyFailDetail != null) {
                totalSystemFailNum += dailyFailDetail["dailySystemFailNum"] as Int
                totalUserFailNum += dailyFailDetail["dailyUserFailNum"] as Int
                totalThirdFailNum += dailyFailDetail["dailyThirdFailNum"] as Int
                totalComponentFailNum += dailyFailDetail["dailyComponentFailNum"] as Int
            }
        }
        // 生成这一段时间总的执行失败详情
        val totalFailDetail = mutableMapOf<String, Any>()
        setTotalFailDetail(totalFailDetail, "systemFailDetail", totalSystemFailNum)
        setTotalFailDetail(totalFailDetail, "userFailDetail", totalUserFailNum)
        setTotalFailDetail(totalFailDetail, "thirdFailDetail", totalThirdFailNum)
        setTotalFailDetail(totalFailDetail, "componentFailDetail", totalComponentFailNum)
        return StoreStatisticTrendData(
            totalFailNum = totalFailNum,
            totalFailDetail = totalFailDetail,
            dailyStatisticList = dailyStatisticList
        )
    }

    private fun setTotalFailDetail(
        totalFailDetail: MutableMap<String, Any>,
        key: String,
        failNum: Int
    ) {
        totalFailDetail[key] = mapOf(NAME to MessageCodeUtil.getCodeLanMessage(key, key), FAIL_NUM to failNum)
    }

    private fun generateStoreStatistic(
        record: Record5<Int, Int, BigDecimal, Int, String>?,
        successRate: Double? = null
    ): StoreStatistic {
        return StoreStatistic(
            downloads = record?.value1() ?: 0,
            commentCnt = record?.value2() ?: 0,
            score = String.format("%.1f", record?.value3()?.toDouble()).toDoubleOrNull(),
            pipelineCnt = record?.value4() ?: 0,
            successRate = successRate
        )
    }

    private fun calculateAndStorage(
        storeType: Byte,
        statistics: Result<Record4<BigDecimal, BigDecimal, BigDecimal, String>>
    ) {
        statistics.forEach {
            // 下载量
            val downloads = it.value1().toInt()
            // 评论数量
            val comments = it.value2().toInt()
            // 评论总分
            val score = it.value3().toDouble()
            val code = it.value4().toString()
            // 评论均分
            val scoreAverage: Double = if (score > 0 && comments > 0) {
                score.div(comments)
            } else 0.toDouble()
            storeStatisticTotalDao.updateStatisticData(
                dslContext = dslContext,
                storeCode = code,
                storeType = storeType,
                downloads = downloads,
                comments = comments,
                score = score.toInt(),
                scoreAverage = scoreAverage
            )
        }
    }
}

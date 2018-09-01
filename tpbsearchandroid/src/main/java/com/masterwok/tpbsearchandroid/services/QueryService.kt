package com.masterwok.tpbsearchandroid.services

import android.util.Log
import com.masterwok.tpbsearchandroid.contracts.QueryService
import com.masterwok.tpbsearchandroid.models.PagedResult
import com.masterwok.tpbsearchandroid.models.SearchResultItem
import kotlinx.coroutines.experimental.JobCancellationException
import kotlinx.coroutines.experimental.TimeoutCancellationException
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.produce
import kotlinx.coroutines.experimental.withTimeout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

class QueryService constructor(
        private val queryFactories: List<(query: String, pageIndex: Int) -> String>
) : QueryService {

    companion object {
        private const val Tag = "QueryService"

        private const val SearchResultPath = "table#searchResult tbody tr"
        private const val TitleSelectPath = "td:nth-child(2) > div"
        private const val MagnetSelectPath = "td:nth-child(2) > a:nth-child(2)"
        private const val SeedersSelectPath = "td:nth-child(3)"
        private const val LeechersSelectPath = "td:nth-child(4)"
        private const val PageSelectPath = "body > div:nth-child(6) > a"

        private val InfoHashRegex = Regex("btih:(.*)&dn")
    }

    private fun makeRequest(url: String) = async<Document> {
        Jsoup.connect(url).get()
    }

    private fun List<PagedResult>.flatten(
            pageIndex: Int
    ): PagedResult = PagedResult(
            pageIndex = pageIndex
            , lastPageIndex = maxBy { it.lastPageIndex }?.lastPageIndex ?: 0
            , items = flatMap { it.items }.distinctBy { it.infoHash }
    )

    private fun producePagedResults(
            queryFactories: List<(query: String, pageIndex: Int) -> String>
            , query: String
            , pageIndex: Int
            , requestTimeout: Long
    ) = produce {
        queryFactories
                .map { async { queryHost(it, query, pageIndex, requestTimeout) } }
                .forEach {
                    val pagedResult = it.await()

                    if (pagedResult.itemCount > 0) {
                        send(pagedResult)
                    }
                }
    }

    override suspend fun query(
            query: String
            , pageIndex: Int
            , requestTimeout: Long
            , maxSuccessfulHosts: Int
    ): PagedResult {
        val producer = producePagedResults(
                queryFactories
                , query
                , pageIndex
                , requestTimeout
        )

        val range = (1..Math.min(queryFactories.size, maxSuccessfulHosts))

        val pagedResults = range.map {
            producer.receive()
        }.flatten(pageIndex)

        producer.cancel()

        return pagedResults
    }

    private suspend fun queryHost(
            queryFactory: (query: String, pageIndex: Int) -> String
            , query: String
            , pageIndex: Int
            , requestTimeout: Long
    ): PagedResult {
        val requestUrl = queryFactory(query, pageIndex)
        var response: Document? = null

        try {
            withTimeout(requestTimeout, TimeUnit.MILLISECONDS) {
                response = makeRequest(requestUrl).await()
            }
        } catch (ex: TimeoutCancellationException) {
            Log.w(Tag, "Request timeout: $requestUrl")
        } catch (ex: JobCancellationException) {
            // Ignored..
        } catch (ex: Exception) {
            Log.w(Tag, "Request failed: $requestUrl")
        }

        return PagedResult(
                pageIndex = pageIndex
                , lastPageIndex = response?.tryParseLastPageIndex() ?: 0
                , items = response?.select(SearchResultPath)
                ?.mapNotNull { it.tryParseSearchResultItem() }
                ?.sortedByDescending { it.seeders }
                ?.distinctBy { it.infoHash }
                ?.toList()
                ?: ArrayList()
        )
    }

    private fun Element.tryParseLastPageIndex(): Int = try {
        val pageCount = Integer.parseInt(select(PageSelectPath)
                ?.dropLast(1)
                ?.last()
                ?.text() ?: "0"
        )

        Math.max(pageCount - 1, 0)
    } catch (ex: Exception) {
        0
    }

    private fun Element.tryParseSearchResultItem(): SearchResultItem? {
        try {
            val magnet = select(MagnetSelectPath)
                    ?.first()
                    ?.attr("href")
                    ?: ""

            return SearchResultItem(
                    title = select(TitleSelectPath)?.first()?.text() ?: ""
                    , magnet = magnet
                    , infoHash = getInfoHash(magnet)
                    , seeders = Integer.parseInt(select(SeedersSelectPath)?.first()?.text() ?: "0")
                    , leechers = Integer.parseInt(select(LeechersSelectPath)?.first()?.text()
                    ?: "0")

            )

        } catch (ex: Exception) {
            return null
        }
    }

    private fun getInfoHash(magnet: String): String = InfoHashRegex
            .find(magnet)
            ?.groupValues
            ?.get(1)
            ?: ""

}
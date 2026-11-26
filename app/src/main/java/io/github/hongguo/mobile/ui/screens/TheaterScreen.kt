// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.github.hongguo.mobile.AppContainer
import io.github.hongguo.mobile.core.CatalogCategory
import io.github.hongguo.mobile.core.DramaSummary
import io.github.hongguo.mobile.core.GenreItem
import io.github.hongguo.mobile.core.SearchCursor
import io.github.hongguo.mobile.core.friendlyErrorMessage
import io.github.hongguo.mobile.ui.components.CategoryTagChips
import io.github.hongguo.mobile.ui.theme.BgBlack
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.GoldAccent
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun TheaterScreen(
    container: AppContainer,
    focusSearchTrigger: Int,
    initialSearchKeyword: String? = null,
    onConsumeInitialSearchKeyword: () -> Unit = {},
    onOpenSeries: (seriesId: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val searchHistory by container.localStore.searchHistory.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var activeSearchKeyword by remember { mutableStateOf("") }
    var searchCursor by remember { mutableStateOf<SearchCursor?>(null) }

    var selectedCategory by remember { mutableStateOf(CatalogCategory.HOT_DRAMA) }
    var selectedGenre by remember { mutableStateOf("") }
    var genres by remember { mutableStateOf<List<GenreItem>>(emptyList()) }

    var items by remember { mutableStateOf<List<DramaSummary>>(emptyList()) }
    var page by remember { mutableIntStateOf(1) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val gridState = rememberLazyGridState()
    val searchFocusRequester = remember { FocusRequester() }
    var requestJob by remember { mutableStateOf<Job?>(null) }
    var requestSeq by remember { mutableIntStateOf(0) }

    fun loadCatalog(reset: Boolean) {
        requestJob?.cancel()
        val mySeq = ++requestSeq
        val cat = selectedCategory
        val genre = selectedGenre
        requestJob = scope.launch {
            loading = true
            errorMsg = null
            try {
                val targetPage = if (reset) 1 else page + 1
                val res = container.apiClient.fetchCatalog(cat, genre, targetPage, forceRefresh = reset)
                if (mySeq != requestSeq) return@launch
                if (reset) {
                    items = res.items
                    page = 1
                    genres = res.genres
                    gridState.scrollToItem(0)
                } else {
                    val seen = items.mapTo(HashSet()) { it.seriesId }
                    items = items + res.items.filter { seen.add(it.seriesId) }
                    page = res.page
                }
                hasMore = res.hasMore
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (mySeq == requestSeq) {
                    errorMsg = friendlyErrorMessage(e, "剧场加载失败，请稍后重试")
                }
            } finally {
                if (mySeq == requestSeq) {
                    loading = false
                }
            }
        }
    }

    fun executeSearch(reset: Boolean) {
        val kw = activeSearchKeyword.trim()
        if (kw.isEmpty()) return
        val directId = container.apiClient.parseSeriesIdFromInput(kw)
        if (reset && directId != null) {
            onOpenSeries(directId)
            return
        }
        if (reset) {
            container.localStore.addSearchHistory(kw)
        }
        requestJob?.cancel()
        val mySeq = ++requestSeq
        requestJob = scope.launch {
            loading = true
            errorMsg = null
            try {
                val targetPage = if (reset) 1 else page + 1
                val res = container.apiClient.searchDramas(kw, targetPage, if (reset) null else searchCursor)
                if (mySeq != requestSeq) return@launch
                if (reset) {
                    items = res.items
                    page = 1
                    gridState.scrollToItem(0)
                } else {
                    val seen = items.mapTo(HashSet()) { it.seriesId }
                    items = items + res.items.filter { seen.add(it.seriesId) }
                    page = res.page
                }
                searchCursor = res.cursor
                hasMore = res.hasMore
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                if (mySeq == requestSeq) {
                    errorMsg = friendlyErrorMessage(e, "搜索失败，请稍后重试")
                }
            } finally {
                if (mySeq == requestSeq) {
                    loading = false
                }
            }
        }
    }

    LaunchedEffect(selectedCategory, selectedGenre, activeSearchKeyword) {
        if (activeSearchKeyword.isEmpty()) {
            loadCatalog(reset = true)
        } else {
            executeSearch(reset = true)
        }
    }

    LaunchedEffect(focusSearchTrigger, initialSearchKeyword) {
        val kw = initialSearchKeyword?.trim()
        if (!kw.isNullOrEmpty()) {
            searchQuery = kw
            searchCursor = null
            if (activeSearchKeyword == kw) {
                executeSearch(reset = true)
            } else {
                activeSearchKeyword = kw
            }
            onConsumeInitialSearchKeyword()
        } else if (focusSearchTrigger > 0) {
            delay(80L)
            runCatching { searchFocusRequester.requestFocus() }
            keyboard?.show()
        }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            items.isNotEmpty() && lastVisible >= items.size - 4 && hasMore && !loading
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            if (activeSearchKeyword.isEmpty()) loadCatalog(reset = false)
            else executeSearch(reset = false)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBlack)
            .statusBarsPadding()
    ) {
        // Search bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .height(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(SurfaceElevated)
                .padding(horizontal = 14.dp)
        ) {
            Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (searchQuery.isEmpty()) {
                    Text(
                        "搜索短剧名 / 粘贴红果链接或剧集ID",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    singleLine = true,
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(CoralRed),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        activeSearchKeyword = searchQuery.trim()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester)
                )
            }
            if (searchQuery.isNotEmpty() || activeSearchKeyword.isNotEmpty()) {
                IconButton(
                    onClick = {
                        searchQuery = ""
                        activeSearchKeyword = ""
                        searchCursor = null
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "清除", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = "搜索",
                color = CoralRed,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable {
                    keyboard?.hide()
                    activeSearchKeyword = searchQuery.trim()
                }
            )
        }

        if (activeSearchKeyword.isEmpty()) {
            if (searchHistory.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    Text("最近搜索", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        text = "清空",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { container.localStore.clearSearchHistory() }
                    )
                }
                Spacer(Modifier.height(4.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchHistory, key = { it }) { kw ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(SurfaceDark)
                                .clickable {
                                    searchQuery = kw
                                    activeSearchKeyword = kw
                                    keyboard?.hide()
                                }
                                .padding(start = 10.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
                        ) {
                            Text(kw, color = TextPrimary, fontSize = 12.sp, maxLines = 1)
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "移除",
                                tint = TextSecondary,
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable { container.localStore.removeSearchHistory(kw) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            // Category pills
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(CatalogCategory.entries) { cat ->
                    val selected = cat == selectedCategory
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (selected) CoralRed else SurfaceDark)
                            .clickable {
                                if (selectedCategory != cat) {
                                    selectedCategory = cat
                                    selectedGenre = ""
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = cat.label,
                            color = if (selected) Color.White else TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Genre filter chips (only for non-ranking categories when genres are returned)
            if (!selectedCategory.isRanking && genres.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        GenreChip(
                            label = "全部题材",
                            selected = selectedGenre.isEmpty(),
                            onClick = { selectedGenre = "" }
                        )
                    }
                    items(genres, key = { it.slug }) { g ->
                        GenreChip(
                            label = g.label,
                            selected = selectedGenre == g.slug,
                            onClick = { selectedGenre = g.slug }
                        )
                    }
                }
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "搜索「$activeSearchKeyword」",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "返回剧场",
                    color = CoralRed,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable {
                        searchQuery = ""
                        activeSearchKeyword = ""
                        searchCursor = null
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (items.isEmpty() && loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CoralRed)
            }
        } else if (items.isEmpty() && errorMsg != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Text(errorMsg ?: "加载失败", color = TextSecondary, fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (activeSearchKeyword.isEmpty()) loadCatalog(reset = true)
                            else executeSearch(reset = true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CoralRed)
                    ) { Text("重试") }
                }
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有找到相关短剧", color = TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.seriesId }) { drama ->
                    DramaPosterCard(drama = drama, onClick = { onOpenSeries(drama.seriesId) })
                }
                if (loading) {
                    item(span = { GridItemSpan(3) }) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                        ) {
                            CircularProgressIndicator(color = CoralRed, modifier = Modifier.size(28.dp))
                        }
                    }
                } else if (errorMsg != null) {
                    item(span = { GridItemSpan(3) }) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp, horizontal = 8.dp)
                        ) {
                            Text(
                                text = errorMsg.orEmpty(),
                                color = TextSecondary,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "点击重试",
                                color = CoralRed,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clickable {
                                    if (activeSearchKeyword.isEmpty()) loadCatalog(reset = false)
                                    else executeSearch(reset = false)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) CoralRed.copy(alpha = 0.2f) else SurfaceElevated)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (selected) CoralRed else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun DramaPosterCard(drama: DramaSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceElevated)
        ) {
            if (drama.cover.isNotEmpty()) {
                AsyncImage(
                    model = drama.cover,
                    contentDescription = drama.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
            )
            drama.rank?.let { r ->
                val badgeColor = when (r) {
                    1 -> CoralRed
                    2 -> Color(0xFFFF7A45)
                    3 -> GoldAccent
                    else -> Color.Black.copy(alpha = 0.65f)
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(badgeColor)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (r <= 3) "TOP $r" else "$r",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            val epText = when {
                drama.episodeLabel.isNotEmpty() -> drama.episodeLabel
                drama.episodeCount > 0 -> "共${drama.episodeCount}集"
                else -> ""
            }
            if (epText.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = epText,
                        color = Color.White.copy(alpha = 0.92f),
                        fontSize = 10.sp
                    )
                }
            }
            CategoryTagChips(
                tags = drama.displayTags,
                gender = drama.gender,
                isNew = drama.isNewTag,
                small = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = drama.title,
            color = TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 17.sp
        )
    }
}

package com.badnng.moe

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.badnng.moe.chat.*
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TextFieldDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Contacts
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** The search surface replaces the chat list; its query and results remain transient. */
internal class ConversationSearchSession(initial: ChatSearchQuery, synchronized: Boolean) {
    val query = mutableStateOf(initial)
    val submitted = mutableStateOf(initial)
    val text = mutableStateOf(TextFieldValue(initial.text))
    val initialSynchronized = mutableStateOf(synchronized)
    val resultsScroll = LazyListState()
    val categoryScroll = ScrollState(0)
    val conditionScroll = ScrollState(0)
    var appliedResultsQuery: ChatSearchQuery? = null
}

@Composable
internal fun ConversationSearchPane(
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    onSelectChat: (Long) -> Unit,
    onJump: (ChatMessage) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: ChatSearchQuery? = null,
    interactive: Boolean = true,
    session: ConversationSearchSession = remember(snapshot.token.generation) {
        ConversationSearchSession(initialQuery ?: snapshot.search.query, initialQuery == null || initialQuery == snapshot.search.query)
    },
) {
    val state = snapshot.search
    val token = snapshot.token
    var query by session.query
    var submitted by session.submitted
    var text by session.text
    var initialSynchronized by session.initialSynchronized
    var chooseAuthor by remember(token.generation) { mutableStateOf(false) }
    var chooseDate by remember(token.generation) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val resultsScroll = session.resultsScroll
    val categoryScroll = session.categoryScroll
    val conditionScroll = session.conditionScroll
    val colors = MiuixTheme.colorScheme
    LaunchedEffect(state.query) {
        if (!initialSynchronized) {
            if (state.query != initialQuery) return@LaunchedEffect
            initialSynchronized = true
        }
        if (state.query != submitted) {
            query = state.query
            text = TextFieldValue(state.query.text)
            submitted = state.query
        }
    }
    LaunchedEffect(token, query, interactive) {
        if (!interactive) return@LaunchedEffect
        if (query != state.query) {
            delay(250)
            submitted = query
            features.search(token, query, false)
        }
    }
    val overview = query.chatId == null && query.text.isBlank() && query.senderId == null &&
        query.minDate == 0 && query.maxDate == 0 && query.category == ChatSearchCategory.CHATS
    val emptyScope = query.chatId != null && query.text.isBlank() && query.senderId == null &&
        query.minDate == 0 && query.maxDate == 0 && query.category == ChatSearchCategory.CHATS
    LaunchedEffect(token, overview, initialSynchronized, interactive) {
        if (interactive && overview && initialSynchronized) features.searchOverview(token)
    }
    val current = query == state.query
    val loading = !current || state.loading
    LaunchedEffect(query) {
        if (session.appliedResultsQuery != query) {
            session.appliedResultsQuery = query
            resultsScroll.scrollToItem(0)
        }
    }
    LaunchedEffect(interactive) { if (!interactive) { chooseAuthor = false; chooseDate = false } }
    val scope = query.chatId?.let { id -> snapshot.knownChats.firstOrNull { it.id == id } ?: snapshot.selected?.takeIf { it.id == id } }
    Column(modifier.background(colors.surface).onPreviewKeyEvent {
        if (it.key == Key.Escape && !chooseAuthor && !chooseDate) {
            if (it.type == KeyEventType.KeyDown) onClose()
            true
        } else false
    }) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val available = constraints.maxDpSize(LocalDensity.current)
            val stackedFilters = available.width < 320.dp
            LaunchedEffect(interactive) { if (interactive) searchFocus.requestFocus() }
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolsIcon(MiuixIcons.Regular.Back, "关闭搜索", onClose)
                    TextField(text, { if (interactive) { text = it; query = query.copy(text = it.text) } },
                        modifier = Modifier.weight(1f).heightIn(min = 42.dp).focusRequester(searchFocus)
                            .semantics { contentDescription = "搜索消息" },
                        label = "搜索", useLabelAsPlaceholder = true, singleLine = true,
                        textStyle = MiuixTheme.textStyles.main.copy(fontSize = 15.sp, lineHeight = 20.sp),
                        insideMargin = DpSize(12.dp, 9.dp), cornerRadius = 21.dp,
                        colors = TextFieldDefaults.textFieldColors(backgroundColor = colors.onSurface.copy(alpha = 0.06f)),
                        trailingIcon = if (text.text.isNotEmpty()) ({
                            SearchFieldClearButton("清除关键词") {
                                text = TextFieldValue()
                                query = query.copy(text = "")
                                searchFocus.requestFocus()
                            }
                        }) else null)
                    if (!stackedFilters) {
                        ToolsIcon(MiuixIcons.Regular.Contacts, "按发送者筛选", { chooseAuthor = true })
                        SearchDateIcon { chooseDate = true }
                    }
                }
                if (stackedFilters) {
                    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("筛选消息", fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                        ToolsIcon(MiuixIcons.Regular.Contacts, "按发送者筛选", { chooseAuthor = true })
                        SearchDateIcon { chooseDate = true }
                    }
                }
            }
        }
        if (query.chatId == null) {
            Row(Modifier.fillMaxWidth().verticalWheelToHorizontal(categoryScroll).horizontalScroll(categoryScroll).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChatSearchCategory.entries.forEach { category ->
                    ToolsChip(searchCategoryLabel(category), query.category == category,
                        onClick = { query = query.copy(category = category) })
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        if (query.chatId != null || query.senderId != null || query.minDate > 0 || query.maxDate > 0) {
            Text("按下列条件搜索消息", fontSize = 12.sp, color = colors.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().verticalWheelToHorizontal(conditionScroll).horizontalScroll(conditionScroll).padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (query.chatId != null) ToolsChip(when {
                    scope?.isChannel == true -> "此频道"
                    scope?.basicGroupId != null || scope?.supergroupId != null -> "此群组"
                    else -> "此聊天"
                }, true, onClick = { query = query.copy(chatId = null) }, removable = true)
                query.senderId?.let { id ->
                    ToolsChip(snapshot.senderNames[id] ?: snapshot.knownChats.firstOrNull { it.privateUserId == id }?.title ?: "指定用户",
                        true, onClick = { query = query.copy(senderId = null) }, removable = true)
                }
                if (query.minDate > 0 || query.maxDate > 0) {
                    val from = conversationSearchDayLabel(query.minDate)
                    val to = conversationSearchDayLabel(query.maxDate)
                    ToolsChip(when { from == to -> from; from.isEmpty() -> "截至 $to"; to.isEmpty() -> "$from 起"; else -> "$from – $to" },
                        true, onClick = { query = query.copy(minDate = 0, maxDate = 0) }, removable = true)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        WorkspaceDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (overview) {
                LazyColumn(Modifier.fillMaxSize(), state = resultsScroll, contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (state.frequent.isNotEmpty()) item {
                        Text("常用联系人", fontSize = 12.sp, color = colors.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(state.frequent, key = { it.id }) { chat ->
                                Column(Modifier.width(68.dp).chatSquircleSurface(colors.surfaceContainer, 15.dp)
                                    .clickable { onSelectChat(chat.id) }.padding(horizontal = 4.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ChatIdentityAvatar(chat, snapshot, features, size = 44.dp)
                                    Text(chat.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                    if (state.recent.isNotEmpty()) item {
                        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("最近", fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                            Text(if (state.recentClearing) "清除中……" else "清除", fontSize = 12.sp,
                                color = if (state.recentClearing) colors.primary.copy(alpha = 0.5f) else colors.primary,
                                modifier = Modifier.chatSquircleSurface(androidx.compose.ui.graphics.Color.Transparent, 15.dp)
                                    .clickable(enabled = !state.recentClearing, role = Role.Button,
                                    onClickLabel = "清除最近搜索", onClick = { features.clearRecentSearch(token) })
                                    .padding(horizontal = 8.dp, vertical = 8.dp))
                        }
                    }
                    items(state.recent, key = { it.id }) { chat -> SearchChatRow(chat, snapshot, features) { onSelectChat(chat.id) } }
                    if (state.overviewLoading) item { ToolsHint("正在加载搜索记录……") }
                    if (!state.overviewLoading && state.recent.isEmpty() && state.frequent.isEmpty()) item { ToolsHint("暂无搜索记录") }
                    state.error?.let { error -> item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatErrorText(error)
                            ChatButton("重新加载", onClick = { features.searchOverview(token) })
                        }
                    } }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = resultsScroll, contentPadding = PaddingValues(vertical = 12.dp)) {
                    if (emptyScope) item { ToolsHint("输入关键词搜索此会话的消息") }
                    if (current && !emptyScope) {
                        items(state.chats, key = { "chat:${it.id}" }) { chat -> SearchChatRow(chat, snapshot, features) { onSelectChat(chat.id) } }
                        items(state.messages, key = { "message:${it.chatId}:${it.id}" }) { message ->
                            SearchMessageRow(message, snapshot, features) { onJump(message) }
                        }
                    }
                    if (loading && !emptyScope) item { ToolsHint("正在搜索……") }
                    else if (current && !emptyScope && state.error != null) item {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatErrorText(state.error)
                            ChatButton("重试搜索", onClick = { features.search(token, query, false) })
                        }
                    } else if (current && !emptyScope && state.chats.isEmpty() && state.messages.isEmpty()) item {
                        ToolsHint(if (query.text.isBlank() && query.chatId != null) "输入关键词搜索此会话的消息" else "没有找到匹配结果")
                    }
                    if (current && !emptyScope && state.nextOffset.isNotEmpty() && !state.loading && state.error == null) item {
                        ChatButton("继续搜索结果", Modifier.fillMaxWidth().padding(14.dp),
                            onClick = { features.search(token, query, true) })
                    }
                }
            }
            WorkspaceScrollbar(resultsScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                .padding(horizontal = 2.dp, vertical = 8.dp))
        }
    }
    if (chooseAuthor && interactive) SearchAuthorDialog(snapshot, features,
        onPick = { query = query.copy(senderId = it); chooseAuthor = false }, onDismiss = { chooseAuthor = false })
    if (chooseDate && interactive) SearchDateDialog(query,
        onPick = { from, to -> query = query.copy(minDate = from, maxDate = to); chooseDate = false },
        onDismiss = { chooseDate = false })
}

/** A real workspace column. Its width and visibility are owned by the responsive parent. */
@Composable
internal fun ConversationInfoPane(
    chat: ChatItem,
    snapshot: ChatSnapshot,
    features: ChatFeatureActions,
    onJump: (ChatMessage) -> Unit,
    onAdmins: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    adminsPopup: @Composable () -> Unit = {},
) {
    val colors = MiuixTheme.colorScheme
    val state = snapshot.details
    val details = state.details?.takeIf { it.chatId == chat.id }
    val token = snapshot.token
    var category by remember(token, chat.id) { mutableStateOf(ChatMediaCategory.PHOTOS_AND_VIDEOS) }
    val mediaState = snapshot.mediaIndex.takeIf { it.chatId == chat.id && it.category == category }
    val media = mediaState?.messages.orEmpty()
    val mediaRows = remember(media, category) { if (category == ChatMediaCategory.PHOTOS_AND_VIDEOS) media.chunked(3) else emptyList() }
    val informationScroll = key(token) { rememberLazyListState() }
    val mediaTabsScroll = rememberLazyListState()
    val nearEnd by remember(informationScroll) { derivedStateOf {
        val layout = informationScroll.layoutInfo
        layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 3 } == true
    } }
    LaunchedEffect(token, category) { features.loadMediaIndex(token, category, false) }
    LaunchedEffect(token, category, nearEnd, mediaState?.nextOffset, mediaState?.loading, mediaState?.loaded, mediaState?.error) {
        if (nearEnd && mediaState?.loaded == true && !mediaState.loading && mediaState.error == null && mediaState.nextOffset.isNotEmpty()) {
            delay(120)
            features.loadMediaIndex(token, category, true)
        }
    }
    val openMedia: (ChatMessage) -> Unit = { message ->
        features.openMediaIndexMessage(token, message.id)
        onJump(message)
    }
    Row(modifier.background(colors.surfaceContainer)) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(colors.dividerLine.copy(alpha = 0.65f)))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("会话信息", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                ToolsIcon(MiuixIcons.Regular.Close, "收起会话信息", onClose)
            }
            WorkspaceDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = informationScroll, contentPadding = PaddingValues(bottom = 20.dp)) {
                item {
                    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChatIdentityAvatar(chat, snapshot, features, size = 72.dp)
                        Text(chat.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val count = snapshot.headerStatus.memberCount ?: chat.memberCount
                        Text(when {
                            count != null -> ("$count 位" + if (chat.isChannel) "订阅者" else "成员") +
                                snapshot.headerStatus.onlineCount?.let { "，$it 位在线" }.orEmpty()
                            chat.isChannel -> "频道"
                            chat.basicGroupId != null || chat.supergroupId != null -> "群组"
                            chat.privateUserId != null -> "私聊"
                            else -> "聊天"
                        }, fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("简介", fontSize = 12.sp, color = colors.primary)
                        when {
                            state.loading -> Text("正在加载……", fontSize = 13.sp, color = colors.onSurfaceVariantSummary)
                            state.error != null -> {
                                ChatErrorText(state.error)
                                ChatButton("重试", onClick = { features.loadChatDetails(snapshot.token) })
                            }
                            else -> Text(details?.description?.takeIf { it.isNotBlank() } ?: "暂无简介", fontSize = 14.sp, lineHeight = 20.sp)
                        }
                        if (chat.basicGroupId != null || chat.supergroupId != null) {
                            Box(Modifier.fillMaxWidth()) {
                                ChatButton("管理员", Modifier.fillMaxWidth(), onClick = onAdmins)
                                adminsPopup()
                            }
                        }
                    }
                }
                item {
                    WorkspaceDivider()
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("共享内容", fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        val count = mediaState?.counts?.get(category) ?: mediaState?.totalCount
                        when {
                            count != null -> Text("$count 项", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            mediaState?.countsLoading?.contains(category) == true ->
                                Text("正在统计……", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                        }
                    }
                    TabRowWithContour(
                        tabs = ChatMediaCategory.entries.map { tab ->
                            val count = snapshot.mediaIndex.takeIf { it.chatId == chat.id }?.counts?.get(tab)
                            conversationMediaCategoryLabel(tab) + count?.let { " $it" }.orEmpty()
                        },
                        selectedTabIndex = category.ordinal,
                        onTabSelected = { category = ChatMediaCategory.entries[it] },
                        modifier = Modifier.padding(horizontal = 12.dp).verticalWheelToHorizontal(mediaTabsScroll),
                        listState = mediaTabsScroll,
                        minWidth = 112.dp,
                        maxWidth = 140.dp,
                        indication = LocalIndication.current,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (mediaState?.countErrors?.containsKey(category) == true && mediaState.totalCount == null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("数量暂不可用", fontSize = 12.sp, color = colors.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                            ChatButton("重试统计", onClick = { features.loadMediaIndex(token, category, false) })
                        }
                    }
                }
                if (category == ChatMediaCategory.PHOTOS_AND_VIDEOS) {
                    items(mediaRows, key = { "media:${it.first().id}" }) { row ->
                        ConversationMediaTileRow(row, snapshot, features, onSelect = openMedia)
                    }
                } else items(media, key = { "${category.name}:${it.id}" }) { message ->
                    SearchMessageRow(message, snapshot, features) { openMedia(message) }
                }
                item {
                    val loading = mediaState == null || mediaState.loading
                    val hasMore = mediaState?.nextOffset?.isNotEmpty() == true
                    when {
                        loading -> ToolsHint("正在加载${conversationMediaCategoryLabel(category)}……")
                        mediaState?.error != null -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ChatErrorText(mediaState.error)
                            ChatButton("重试", Modifier.fillMaxWidth(), onClick = {
                                features.loadMediaIndex(token, category, mediaState.loaded && hasMore)
                            })
                        }
                        mediaState?.loaded == false -> Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("内容已更新", fontSize = 12.sp, color = colors.onSurfaceVariantSummary)
                            ChatButton("刷新", Modifier.fillMaxWidth(), onClick = { features.loadMediaIndex(token, category, false) })
                        }
                        media.isEmpty() && !hasMore -> ToolsHint("暂无${conversationMediaCategoryLabel(category)}")
                    }
                    if (!loading && mediaState?.loaded == true && mediaState.error == null && hasMore) {
                        ChatButton("加载更多", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            onClick = { features.loadMediaIndex(token, category, true) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchAuthorDialog(snapshot: ChatSnapshot, features: ChatFeatureActions, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    var query by remember(snapshot.token) { mutableStateOf(TextFieldValue()) }
    val users = remember(snapshot.senderNames, snapshot.knownChats) {
        snapshot.senderNames + snapshot.knownChats.mapNotNull { chat -> chat.privateUserId?.let { it to chat.title } }.toMap()
    }
    val shown = remember(users, query.text) { users.filter { it.key > 0 && it.value.contains(query.text.trim(), ignoreCase = true) }.toList() }
    WindowDialog(true, title = "按发送者搜索", maxWidth = 380.dp, onDismissRequest = onDismiss) {
        Column(Modifier.heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextField(query, { query = it }, label = "搜索已知用户", singleLine = true, modifier = Modifier.fillMaxWidth())
            ChatButton("任何发送者", Modifier.fillMaxWidth(), onClick = { onPick(null) })
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(shown, key = { it.first }) { (id, name) ->
                    Row(Modifier.fillMaxWidth().chatSquircleSurface(androidx.compose.ui.graphics.Color.Transparent, 15.dp)
                        .clickable { onPick(id) }.padding(horizontal = 8.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolsUserAvatar(id, name, snapshot, features)
                        Text(name.ifBlank { "用户" }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (shown.isEmpty()) item { ToolsHint("没有匹配的已知用户") }
            }
            ChatButton("取消", Modifier.fillMaxWidth(), onClick = onDismiss)
        }
    }
}

@Composable
private fun SearchDateDialog(query: ChatSearchQuery, onPick: (Int, Int) -> Unit, onDismiss: () -> Unit) {
    var from by remember { mutableStateOf(TextFieldValue(conversationSearchDayLabel(query.minDate))) }
    var to by remember { mutableStateOf(TextFieldValue(conversationSearchDayLabel(query.maxDate))) }
    val fromDay = if (from.text.isBlank()) null else conversationSearchDayRange(from.text.trim())
    val toDay = if (to.text.isBlank()) null else conversationSearchDayRange(to.text.trim())
    val valid = (from.text.isBlank() || fromDay != null) && (to.text.isBlank() || toDay != null) &&
        (fromDay == null || toDay == null || fromDay.first <= toDay.second)
    WindowDialog(true, title = "按日期搜索", maxWidth = 360.dp, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("使用本地日期，可仅填写一端。", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            TextField(from, { from = it }, label = "开始日期 YYYY-MM-DD", singleLine = true, modifier = Modifier.fillMaxWidth())
            TextField(to, { to = it }, label = "结束日期 YYYY-MM-DD", singleLine = true, modifier = Modifier.fillMaxWidth())
            if (!valid) Text("请填写有效日期，结束日期不能早于开始日期。", fontSize = 12.sp, color = MiuixTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChatButton("清除", Modifier.weight(1f), onClick = { onPick(0, 0) })
                ChatButton("取消", Modifier.weight(1f), onClick = onDismiss)
                ChatButton("应用", Modifier.weight(1f), enabled = valid, primary = true,
                    onClick = { onPick(fromDay?.first ?: 0, toDay?.second ?: 0) })
            }
        }
    }
}

@Composable
private fun SearchChatRow(chat: ChatItem, snapshot: ChatSnapshot, features: ChatFeatureActions, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).chatSquircleSurface(androidx.compose.ui.graphics.Color.Transparent, 15.dp)
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatIdentityAvatar(chat, snapshot, features, size = 42.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chat.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(chat.lastMessage?.let(::messagePreview) ?: if (chat.isChannel) "频道" else "聊天", fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun SearchMessageRow(message: ChatMessage, snapshot: ChatSnapshot, features: ChatFeatureActions, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp).chatSquircleSurface(androidx.compose.ui.graphics.Color.Transparent, 15.dp)
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        SenderIdentityAvatar(message, snapshot, features, visible = true, size = 36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(snapshot.senderLabel(message), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(chatListTime(message.date), fontSize = 10.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Text(messagePreview(message), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (message.chatId != snapshot.token.chatId) snapshot.knownChats.firstOrNull { it.id == message.chatId }?.let {
                Text(it.title, fontSize = 11.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun ToolsUserAvatar(id: Long, name: String, snapshot: ChatSnapshot, features: ChatFeatureActions) {
    val avatar = snapshot.senderAvatars[id] ?: snapshot.account?.takeIf { it.userId == id }?.avatar ?: snapshot.knownChats.firstOrNull { it.privateUserId == id }?.avatar
    if (avatar != null) RemoteMedia(MessageMedia(avatar), snapshot, features, visible = true, animate = false, compact = true,
        modifier = Modifier.size(36.dp).clip(CircleShape), cornerRadius = 0.dp, retainWhenInactive = true,
        contentScale = ContentScale.Crop, highQuality = true,
        placeholder = { IdentityAvatarPlaceholder(id, name, 36.dp) })
    else IdentityAvatarPlaceholder(id, name, 36.dp)
}

@Composable
private fun ToolsIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = label, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(19.dp), tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}

@Composable
private fun SearchDateIcon(onClick: () -> Unit) {
    val color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Box(Modifier.size(30.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = "按日期筛选", onClick = onClick)
        .semantics { contentDescription = "按日期筛选" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(19.dp)) {
            val stroke = 1.6.dp.toPx()
            drawRoundRect(color, Offset(size.width * 0.1f, size.height * 0.22f), Size(size.width * 0.8f, size.height * 0.7f),
                CornerRadius(2.dp.toPx()), style = Stroke(stroke))
            drawLine(color, Offset(size.width * 0.1f, size.height * 0.43f), Offset(size.width * 0.9f, size.height * 0.43f), stroke)
            listOf(0.3f, 0.7f).forEach { x -> drawLine(color, Offset(size.width * x, size.height * 0.08f), Offset(size.width * x, size.height * 0.32f), stroke) }
        }
    }
}

@Composable
private fun ToolsChip(label: String, selected: Boolean, onClick: () -> Unit, removable: Boolean = false) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.chatSquircleSurface(if (selected) colors.tertiaryContainer else colors.surfaceContainer, 15.dp)
        .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, maxLines = 1, color = if (selected) colors.primary else colors.onSurfaceVariantSummary)
        if (removable) Icon(MiuixIcons.Regular.Close, "取消筛选", Modifier.size(12.dp), tint = colors.primary)
    }
}

@Composable
private fun ToolsHint(text: String) {
    Text(text, fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp))
}

private fun searchCategoryLabel(category: ChatSearchCategory): String = when (category) {
    ChatSearchCategory.CHATS -> "聊天"
    ChatSearchCategory.CHANNELS -> "频道"
    ChatSearchCategory.APPS -> "应用"
    ChatSearchCategory.POSTS -> "帖子"
    ChatSearchCategory.PHOTOS -> "图片"
    ChatSearchCategory.VIDEOS -> "视频"
    ChatSearchCategory.FILES -> "文件"
    ChatSearchCategory.LINKS -> "链接"
    ChatSearchCategory.MUSIC -> "音乐"
    ChatSearchCategory.VOICE -> "语音"
}

private fun conversationMediaCategoryLabel(category: ChatMediaCategory): String = when (category) {
    ChatMediaCategory.PHOTOS_AND_VIDEOS -> "照片与视频"
    ChatMediaCategory.FILES -> "文件"
    ChatMediaCategory.LINKS -> "链接"
    ChatMediaCategory.AUDIO -> "音乐"
    ChatMediaCategory.VOICE -> "语音"
}

internal expect fun conversationSearchDayRange(text: String): Pair<Int, Int>?
internal expect fun conversationSearchDayLabel(epochSeconds: Int): String

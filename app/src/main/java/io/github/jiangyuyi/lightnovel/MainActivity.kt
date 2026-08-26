package io.github.jiangyuyi.lightnovel

import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import io.github.jiangyuyi.lightnovel.core.ui.LightNovelTheme
import io.github.jiangyuyi.lightnovel.core.ui.LocalAppIconScale
import io.github.jiangyuyi.lightnovel.core.ui.viewModelFactory
import io.github.jiangyuyi.lightnovel.feature.app.AppViewModel
import io.github.jiangyuyi.lightnovel.feature.auth.AuthScreen
import io.github.jiangyuyi.lightnovel.feature.auth.AuthViewModel
import io.github.jiangyuyi.lightnovel.feature.account.PublishingScreen
import io.github.jiangyuyi.lightnovel.feature.account.PublishingViewModel
import io.github.jiangyuyi.lightnovel.feature.account.SocialMode
import io.github.jiangyuyi.lightnovel.feature.account.SocialScreen
import io.github.jiangyuyi.lightnovel.feature.account.SocialViewModel
import io.github.jiangyuyi.lightnovel.feature.book.BookScreen
import io.github.jiangyuyi.lightnovel.feature.book.BookViewModel
import io.github.jiangyuyi.lightnovel.feature.bookshelf.AggregateBookshelfScreen
import io.github.jiangyuyi.lightnovel.feature.bookshelf.AggregateBookshelfViewModel
import io.github.jiangyuyi.lightnovel.feature.discover.AggregateDiscoverScreen
import io.github.jiangyuyi.lightnovel.feature.discover.AggregateDiscoverViewModel
import io.github.jiangyuyi.lightnovel.feature.history.AggregateHistoryScreen
import io.github.jiangyuyi.lightnovel.feature.history.AggregateHistoryViewModel
import io.github.jiangyuyi.lightnovel.feature.profile.ProfileScreen
import io.github.jiangyuyi.lightnovel.feature.profile.ProfileViewModel
import io.github.jiangyuyi.lightnovel.feature.profile.SettingsScreen
import io.github.jiangyuyi.lightnovel.feature.local.LocalLibraryScreen
import io.github.jiangyuyi.lightnovel.feature.local.LocalReaderScreen
import io.github.jiangyuyi.lightnovel.feature.messages.DmThreadScreen
import io.github.jiangyuyi.lightnovel.feature.messages.DmThreadViewModel
import io.github.jiangyuyi.lightnovel.feature.messages.MessagesScreen
import io.github.jiangyuyi.lightnovel.feature.messages.MessagesViewModel
import io.github.jiangyuyi.lightnovel.core.model.UserSummary
import io.github.jiangyuyi.lightnovel.core.model.DmConversation
import io.github.jiangyuyi.lightnovel.feature.reader.ReaderScreen
import io.github.jiangyuyi.lightnovel.feature.reader.ReaderViewModel
import io.github.jiangyuyi.lightnovel.feature.reader.SourceReaderScreen
import io.github.jiangyuyi.lightnovel.feature.reader.SourceReaderViewModel
import io.github.jiangyuyi.lightnovel.feature.search.AggregateSearchScreen
import io.github.jiangyuyi.lightnovel.feature.search.AggregateSearchViewModel
import io.github.jiangyuyi.lightnovel.feature.sources.SourceAccountsScreen
import io.github.jiangyuyi.lightnovel.feature.sources.SourceAccountsViewModel
import io.github.jiangyuyi.lightnovel.feature.sources.SourceBookScreen
import io.github.jiangyuyi.lightnovel.feature.sources.SourceBookViewModel
import io.github.jiangyuyi.lightnovel.core.source.ChapterKey
import io.github.jiangyuyi.lightnovel.core.source.NovelKey

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LightNovelAppRoot() }
    }
}

@Composable
private fun LightNovelAppRoot() {
    val application = LocalContext.current.applicationContext as LightNovelApplication
    val appPreferences by application.container.appPreferences.preferences
        .collectAsStateWithLifecycle(initialValue = io.github.jiangyuyi.lightnovel.core.preferences.AppPreferences())
    LightNovelTheme(appPreferences) { LightNovelApp() }
}

private object Routes {
    const val DISCOVER = "discover"
    const val BOOKSHELF = "bookshelf"
    const val SEARCH = "search"
    const val PROFILE = "profile"
    const val LOCAL = "local"
    const val DOWNLOADS = "downloads"
    const val SETTINGS = "settings"
    const val AUTH = "auth"
    const val SOCIAL = "social/{mode}"
    const val HISTORY = "history"
    const val PUBLISHING = "publishing"
    const val MESSAGES = "messages"
    const val DM_THREAD = "dm/{peerUid}/{nickname}"
    const val BOOK = "book/{bookId}"
    const val READER = "reader/{bookId}/{chapterId}"
    const val SOURCE_ACCOUNTS = "source-accounts"
    const val SOURCE_ACCOUNT = "source-account/{sourceId}"
    const val SOURCE_BOOK = "source-book/{sourceId}/{remoteId}"
    const val SOURCE_READER = "source-reader/{sourceId}/{bookId}/{chapterId}"
    const val LOCAL_READER = "local-reader/{bookId}"

    fun book(id: Long) = "book/$id"
    fun reader(bookId: Long, chapterId: Long) = "reader/$bookId/$chapterId"
    fun sourceBook(key: NovelKey) =
        "source-book/${Uri.encode(key.sourceId)}/${Uri.encode(key.remoteId)}"
    fun sourceReader(novelKey: NovelKey, chapterKey: ChapterKey) =
        "source-reader/${Uri.encode(novelKey.sourceId)}/${Uri.encode(novelKey.remoteId)}/${Uri.encode(chapterKey.remoteId)}"
    fun social(mode: SocialMode) = "social/${mode.name.lowercase()}"
    fun dm(conversation: DmConversation) =
        "dm/${conversation.peerUid}/${Uri.encode(conversation.user.nickname)}"
    fun localReader(id: String) = "local-reader/${Uri.encode(id)}"
    fun sourceAccounts(sourceId: String) = "source-account/${Uri.encode(sourceId)}"
}

private data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

private val bottomDestinations = listOf(
    BottomDestination(Routes.DISCOVER, "发现", Icons.Filled.Home),
    BottomDestination(Routes.BOOKSHELF, "书架", Icons.AutoMirrored.Filled.MenuBook),
    BottomDestination(Routes.SEARCH, "搜索", Icons.Filled.Search),
    BottomDestination(Routes.PROFILE, "我的", Icons.Filled.Person),
    BottomDestination(Routes.LOCAL, "本地", Icons.Filled.AutoStories),
)

@Composable
private fun LightNovelApp() {
    val application = LocalContext.current.applicationContext as LightNovelApplication
    val container = application.container
    val navController = rememberNavController()
    val appViewModel: AppViewModel = viewModel(factory = viewModelFactory { AppViewModel(container.repository) })
    val session by appViewModel.session.collectAsStateWithLifecycle()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBottomBar = bottomDestinations.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomDestinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = { navController.openRoot(destination.route) },
                            icon = { androidx.compose.material3.Icon(destination.icon, contentDescription = destination.label, modifier = Modifier.size((24 * LocalAppIconScale.current).dp)) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DISCOVER,
            modifier = Modifier.padding(
                if (currentRoute == Routes.READER || currentRoute == Routes.SOURCE_READER) {
                    PaddingValues(0.dp)
                } else {
                    padding
                },
            ),
        ) {
            composable(Routes.DISCOVER) {
                val vm: AggregateDiscoverViewModel = viewModel(
                    factory = viewModelFactory { AggregateDiscoverViewModel(container.sourceRegistry) },
                )
                AggregateDiscoverScreen(
                    viewModel = vm,
                    onBook = { navController.navigate(Routes.sourceBook(it)) },
                    onAccounts = { navController.navigate(Routes.SOURCE_ACCOUNTS) },
                )
            }
            composable(Routes.BOOKSHELF) {
                val vm: AggregateBookshelfViewModel = viewModel(
                    factory = viewModelFactory {
                        AggregateBookshelfViewModel(
                            container.sourceRegistry,
                            container.offlineLibrary,
                            container.sourceUpdateSnapshots,
                        )
                    },
                )
                AggregateBookshelfScreen(
                    viewModel = vm,
                    onBook = { navController.navigate(Routes.sourceBook(it)) },
                    onAccounts = { navController.navigate(Routes.SOURCE_ACCOUNTS) },
                )
            }
            composable(Routes.SEARCH) {
                val vm: AggregateSearchViewModel = viewModel(
                    factory = viewModelFactory {
                        AggregateSearchViewModel(container.aggregateSearch, container.sourceRegistry)
                    },
                )
                AggregateSearchScreen(
                    viewModel = vm,
                    onBook = { navController.navigate(Routes.sourceBook(it)) },
                    onAccounts = { navController.navigate(Routes.SOURCE_ACCOUNTS) },
                )
            }
            composable(Routes.PROFILE) {
                val vm: ProfileViewModel = viewModel(factory = viewModelFactory { ProfileViewModel(container.repository, container.sourceRegistry) })
                ProfileScreen(
                    viewModel = vm,
                    session = session,
                    onLogin = { navController.navigate(Routes.AUTH) },
                    onLogout = appViewModel::logout,
                    onFollowing = { navController.navigate(Routes.social(SocialMode.FOLLOWING)) },
                    onFollowers = { navController.navigate(Routes.social(SocialMode.FOLLOWERS)) },
                    onHistory = { navController.navigate(Routes.HISTORY) },
                    onPublishing = { navController.navigate(Routes.PUBLISHING) },
                    onMessages = { navController.navigate(Routes.MESSAGES) },
                    onSourceAccount = { sourceId -> navController.navigate(Routes.sourceAccounts(sourceId)) },
                    onDownloads = { navController.navigate(Routes.DOWNLOADS) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.LOCAL) {
                LocalLibraryScreen(
                    store = container.localLibrary,
                    onBook = { book -> navController.navigate(Routes.localReader(book.id)) },
                )
            }
            composable(
                Routes.LOCAL_READER,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                val bookId = entry.arguments?.getString("bookId") ?: return@composable
                val book = container.localLibrary.books.collectAsStateWithLifecycle().value
                    .firstOrNull { it.id == bookId } ?: return@composable
                LocalReaderScreen(
                    store = container.localLibrary,
                    record = book,
                    preferencesStore = container.readerPreferences,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    offlineLibrary = container.offlineLibrary,
                    updateNotifications = container.updateNotifications,
                    readerPreferences = container.readerPreferences,
                    appPreferences = container.appPreferences,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(Routes.DOWNLOADS) {
                val vm: AggregateBookshelfViewModel = viewModel(
                    key = "downloads",
                    factory = viewModelFactory {
                        AggregateBookshelfViewModel(
                            container.sourceRegistry,
                            container.offlineLibrary,
                            container.sourceUpdateSnapshots,
                            initialDownloadedOnly = true,
                        )
                    },
                )
                AggregateBookshelfScreen(
                    viewModel = vm,
                    onBook = { navController.navigate(Routes.sourceBook(it)) },
                    onAccounts = { navController.navigate(Routes.SOURCE_ACCOUNTS) },
                    title = "下载与导出",
                    onBack = { navController.popBackStack() },
                    showTabs = false,
                )
            }
            composable(Routes.SOURCE_ACCOUNTS) {
                val vm: SourceAccountsViewModel = viewModel(
                    factory = viewModelFactory { SourceAccountsViewModel(container.sourceRegistry) },
                )
                SourceAccountsScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(
                Routes.SOURCE_ACCOUNT,
                arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
            ) { entry ->
                val sourceId = entry.arguments?.getString("sourceId") ?: return@composable
                val vm: SourceAccountsViewModel = viewModel(
                    key = "source-accounts-$sourceId",
                    factory = viewModelFactory { SourceAccountsViewModel(container.sourceRegistry) },
                )
                SourceAccountsScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    focusSourceId = sourceId,
                )
            }
            composable(
                Routes.SOCIAL,
                arguments = listOf(navArgument("mode") { type = NavType.StringType }),
            ) { entry ->
                val initialMode = if (entry.arguments?.getString("mode") == "followers") {
                    SocialMode.FOLLOWERS
                } else SocialMode.FOLLOWING
                val vm: SocialViewModel = viewModel(
                    key = "social-${initialMode.name}",
                    factory = viewModelFactory { SocialViewModel(container.repository, initialMode) },
                )
                SocialScreen(vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.HISTORY) {
                val vm: AggregateHistoryViewModel = viewModel(
                    factory = viewModelFactory {
                        AggregateHistoryViewModel(container.sourceRegistry, container.readerPreferences)
                    },
                )
                AggregateHistoryScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onOpen = { novelKey, chapterKey ->
                        navController.navigate(
                            chapterKey?.let { Routes.sourceReader(novelKey, it) }
                                ?: Routes.sourceBook(novelKey),
                        )
                    },
                    onAccounts = { navController.navigate(Routes.SOURCE_ACCOUNTS) },
                )
            }
            composable(Routes.PUBLISHING) {
                val vm: PublishingViewModel = viewModel(factory = viewModelFactory { PublishingViewModel(container.repository) })
                PublishingScreen(
                    vm,
                    onBack = { navController.popBackStack() },
                    onBook = { navController.navigate(Routes.book(it)) },
                )
            }
            composable(Routes.MESSAGES) {
                val vm: MessagesViewModel = viewModel(factory = viewModelFactory {
                    MessagesViewModel(container.repository)
                })
                MessagesScreen(
                    vm,
                    onBack = { navController.popBackStack() },
                    onConversation = { navController.navigate(Routes.dm(it)) },
                    onTarget = { bookId, chapterId ->
                        navController.navigate(chapterId?.let { Routes.reader(bookId, it) } ?: Routes.book(bookId))
                    },
                )
            }
            composable(
                Routes.DM_THREAD,
                arguments = listOf(
                    navArgument("peerUid") { type = NavType.LongType },
                    navArgument("nickname") { type = NavType.StringType },
                ),
            ) { entry ->
                val peerUid = entry.arguments?.getLong("peerUid") ?: return@composable
                val nickname = entry.arguments?.getString("nickname").orEmpty().ifBlank { "用户$peerUid" }
                val peer = UserSummary(peerUid, nickname)
                val vm: DmThreadViewModel = viewModel(
                    key = "dm-$peerUid",
                    factory = viewModelFactory { DmThreadViewModel(peer, container.repository) },
                )
                DmThreadScreen(peer, vm, onBack = { navController.popBackStack() })
            }
            composable(Routes.AUTH) {
                val vm: AuthViewModel = viewModel(factory = viewModelFactory { AuthViewModel(container.repository) })
                AuthScreen(vm, onBack = { navController.popBackStack() }, onCompleted = { navController.popBackStack() })
            }
            composable(
                Routes.BOOK,
                arguments = listOf(navArgument("bookId") { type = NavType.LongType }),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: return@composable
                val vm: BookViewModel = viewModel(
                    key = "book-$bookId",
                    factory = viewModelFactory { BookViewModel(bookId, container.repository) },
                )
                BookScreen(
                    vm,
                    onBack = { navController.popBackStack() },
                    onLogin = { navController.navigate(Routes.AUTH) },
                    onBook = { navController.navigate(Routes.book(it)) },
                    onRead = { navController.navigate(Routes.reader(bookId, it)) },
                )
            }
            composable(
                Routes.READER,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.LongType },
                    navArgument("chapterId") { type = NavType.LongType },
                ),
            ) { entry ->
                val bookId = entry.arguments?.getLong("bookId") ?: return@composable
                val chapterId = entry.arguments?.getLong("chapterId") ?: return@composable
                val vm: ReaderViewModel = viewModel(
                    key = "reader-$bookId-$chapterId",
                    factory = viewModelFactory {
                        ReaderViewModel(bookId, chapterId, container.repository, container.readerPreferences)
                    },
                )
                ReaderScreen(
                    vm,
                    onBack = { navController.popBackStack() },
                    onCatalog = { navController.navigate(Routes.book(bookId)) },
                )
            }
            composable(
                Routes.SOURCE_BOOK,
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("remoteId") { type = NavType.StringType },
                ),
            ) { entry ->
                val sourceId = entry.arguments?.getString("sourceId") ?: return@composable
                val remoteId = entry.arguments?.getString("remoteId") ?: return@composable
                val novelKey = NovelKey(sourceId, remoteId)
                val vm: SourceBookViewModel = viewModel(
                    key = "source-book-$sourceId-$remoteId",
                    factory = viewModelFactory {
                        SourceBookViewModel(
                            novelKey,
                            container.sourceRegistry,
                            container.offlineLibrary,
                            container.readerPreferences,
                        )
                    },
                )
                SourceBookScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onBook = { navController.navigate(Routes.sourceBook(it)) },
                    onRead = { navController.navigate(Routes.sourceReader(novelKey, it)) },
                    onAccounts = { navController.navigate(Routes.SOURCE_ACCOUNTS) },
                )
            }
            composable(
                Routes.SOURCE_READER,
                arguments = listOf(
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument("chapterId") { type = NavType.StringType },
                ),
            ) { entry ->
                val sourceId = entry.arguments?.getString("sourceId") ?: return@composable
                val bookId = entry.arguments?.getString("bookId") ?: return@composable
                val chapterId = entry.arguments?.getString("chapterId") ?: return@composable
                val novelKey = NovelKey(sourceId, bookId)
                val chapterKey = ChapterKey(sourceId, chapterId)
                val vm: SourceReaderViewModel = viewModel(
                    key = "source-reader-$sourceId-$bookId-$chapterId",
                    factory = viewModelFactory {
                        SourceReaderViewModel(
                            novelKey,
                            chapterKey,
                            container.sourceRegistry,
                            container.readerPreferences,
                            container.offlineLibrary,
                            container.chapterFonts,
                        )
                    },
                )
                SourceReaderScreen(
                    viewModel = vm,
                    onBack = { navController.popBackStack() },
                    onCatalog = { navController.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.openRoot(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

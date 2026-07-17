package app.clearspace.network

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import app.clearspace.network.networking.SharingToggleViewModel
import app.clearspace.network.ui.components.GlobalSharingToggle
import app.clearspace.network.ui.components.TofuConfirmationHost
import app.clearspace.network.ui.feed.SharedBrowseScreen
import app.clearspace.network.ui.feed.SharedBrowseViewModel
import app.clearspace.network.ui.social.SocialScreen
import app.clearspace.network.ui.social.GroupsScreen
import app.clearspace.network.ui.library.LibraryScreen
import app.clearspace.network.ui.content.CreateContentScreen
import app.clearspace.network.ui.profile.ProfileScreen
import app.clearspace.network.ui.trust.RemoteLinkExchangeScreen
import app.clearspace.network.ui.trust.TrustVerificationScreen
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun MainNavigation(
    sharingToggleViewModel: SharingToggleViewModel,
    sharedBrowseViewModel: SharedBrowseViewModel,
    spaceViewModel: app.clearspace.network.ui.feed.SpaceViewModel,
    initialDeepLinkTitle: String? = null
) {
  val backStack = rememberNavBackStack(FeedTab)
  
  androidx.compose.runtime.LaunchedEffect(initialDeepLinkTitle) {
      if (initialDeepLinkTitle != null) {
          backStack.add(CreateContent(initialDeepLinkTitle))
      }
  }
  val isSharingEnabled by sharingToggleViewModel.isSharingEnabled.collectAsState()

  // Find current tab
  val currentKey = backStack.lastOrNull() ?: FeedTab
  val isMainTab = currentKey == FeedTab || currentKey == SocialTab || currentKey == LibraryTab

  val drawerState = androidx.compose.material3.rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
  val scope = rememberCoroutineScope()

  androidx.compose.material3.ModalNavigationDrawer(
      drawerState = drawerState,
      drawerContent = {
          androidx.compose.material3.ModalDrawerSheet {
              androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
              Text("ClearSpace", modifier = Modifier.padding(16.dp), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
              androidx.compose.material3.HorizontalDivider()
              androidx.compose.material3.NavigationDrawerItem(
                  label = { Text("Profile") },
                  selected = false,
                  onClick = { 
                      scope.launch { drawerState.close() }
                      backStack.add(ProfileSettings) 
                  },
                  icon = { Icon(Icons.Filled.Person, contentDescription = null) }
              )
              androidx.compose.material3.NavigationDrawerItem(
                  label = { Text("About") },
                  selected = false,
                  onClick = { 
                      scope.launch { drawerState.close() }
                      backStack.add(AboutSettings) 
                  },
                  icon = { Icon(Icons.Filled.Info, contentDescription = null) }
              )
          }
      }
  ) {
      Scaffold(
          topBar = {
              if (isMainTab) {
                  androidx.compose.material3.TopAppBar(
                      title = { 
                          val title = when (currentKey) {
                              FeedTab -> "Explore"
                              SocialTab -> "Network"
                              LibraryTab -> "Vault"
                              else -> "ClearSpace"
                          }
                          Text(title)
                      },
                      navigationIcon = {
                          androidx.compose.material3.IconButton(onClick = { scope.launch { drawerState.open() } }) {
                              Icon(Icons.Filled.AccountCircle, contentDescription = "Menu")
                          }
                      },
                      actions = {
                          androidx.compose.material3.IconButton(onClick = { backStack.add(GlobalSearch) }) {
                              Icon(Icons.Filled.Search, contentDescription = "Global Search")
                          }
                          GlobalSharingToggle(
                              isSharingEnabled = isSharingEnabled,
                              onEnableSharing = { sharingToggleViewModel.enableSharing() },
                              onDisableSharing = { sharingToggleViewModel.disableSharing() }
                          )
                      }
                  )
              }
          },
          bottomBar = {
                  if (isMainTab) {
                      NavigationBar {
                          NavigationBarItem(
                          selected = currentKey == FeedTab,
                          onClick = { backStack.add(FeedTab) },
                          icon = { Icon(Icons.Filled.Public, contentDescription = "Explore") },
                          label = { Text("Explore") }
                      )
                      NavigationBarItem(
                          selected = currentKey == SocialTab,
                          onClick = { backStack.add(SocialTab) },
                          icon = { Icon(Icons.Filled.Person, contentDescription = "Network") },
                          label = { Text("Network") }
                      )
                      NavigationBarItem(
                          selected = currentKey == LibraryTab,
                          onClick = { backStack.add(LibraryTab) },
                          icon = { Icon(Icons.Filled.List, contentDescription = "Vault") },
                          label = { Text("Vault") }
                      )
                      }
                  }
          },
          floatingActionButton = {
              if (isMainTab) {
                  androidx.compose.material3.FloatingActionButton(
                      onClick = { backStack.add(CreateContent()) }
                  ) {
                      Icon(Icons.Filled.Add, contentDescription = "Create Content")
                  }
              }
          }
      ) { paddingValues ->
          // FIX C2: Show TOFU fingerprint confirmation dialog whenever a new peer connects
          TofuConfirmationHost()
      NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider =
          entryProvider {
            entry<FeedTab> {
              app.clearspace.network.ui.feed.SpaceDirectoryScreen(
                  viewModel = spaceViewModel,
                  onSpaceSelected = { space ->
                      backStack.add(SpaceFeed(space.spaceId, space.name))
                  },
                  onCreateSpace = {
                      backStack.add(CreateSpace)
                  }
              )
            }
            entry<SpaceFeed> { key ->
              SharedBrowseScreen(
                  modifier = Modifier.padding(paddingValues), 
                  viewModel = sharedBrowseViewModel,
                  spaceId = key.spaceId,
                  spaceName = key.spaceName,
                  onShareToGroup = { title, body ->
                      backStack.add(CreateContent(prefillTitle = title, prefillBody = body, forceVisibilityGroup = true))
                  },
                  onBack = { backStack.removeLastOrNull() },
                  onCreateContent = { backStack.add(CreateContent(spaceId = key.spaceId)) }
              )
            }
            entry<CreateSpace> {
              val context = androidx.compose.ui.platform.LocalContext.current
              val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
              val profileAlias = prefs.getString("profile_alias", "Anonymous") ?: "Anonymous"
              app.clearspace.network.ui.feed.CreateSpaceScreen(
                  onBack = { backStack.removeLastOrNull() },
                  viewModel = spaceViewModel,
                  creatorAlias = profileAlias
              )
            }
            entry<SocialTab> {
              SocialScreen(
                  modifier = Modifier.padding(paddingValues),
                  onNavigateToGroups = { backStack.add(ManageGroups) },
                  onNavigateToTrust = { backStack.add(RemoteLinkExchange) }
              )
            }
            entry<LibraryTab> {
              LibraryScreen(
                  modifier = Modifier.padding(paddingValues),
                  onNavigateToMediaVault = { backStack.add(MediaVault) },
                  onNavigateToGraph = { backStack.add(GraphView) }
              )
            }
            entry<CreateContent> { key ->
              CreateContentScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onNavigateBack = { backStack.removeLastOrNull() },
                  initialTitle = key.prefillTitle ?: "",
                  initialBody = key.prefillBody ?: "",
                  forceVisibilityGroup = key.forceVisibilityGroup,
                  spaceId = key.spaceId
              )
            }
            entry<ProfileSettings> {
              ProfileScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onNavigateBack = { backStack.removeLastOrNull() }
              )
            }
            entry<AboutSettings> {
              app.clearspace.network.ui.about.AboutScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onNavigateBack = { backStack.removeLastOrNull() },
                  onNavigateToLicenses = { backStack.add(LicensesSettings) }
              )
            }
            entry<LicensesSettings> {
              app.clearspace.network.ui.about.OpenSourceLicensesScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onNavigateBack = { backStack.removeLastOrNull() }
              )
            }
            entry<MediaVault> {
              app.clearspace.network.ui.library.MediaVaultScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onNavigateBack = { backStack.removeLastOrNull() }
              )
            }
            entry<GraphView> {
              app.clearspace.network.ui.library.GraphViewScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onNavigateBack = { backStack.removeLastOrNull() }
              )
            }
            entry<ManageGroups> {
              GroupsScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onBack = { backStack.removeLastOrNull() }
              )
            }
            entry<RemoteLinkExchange> {
              RemoteLinkExchangeScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onBack = { backStack.removeLastOrNull() },
                  onNavigateToScanner = { backStack.add(TrustVerification) }
              )
            }
            entry<TrustVerification> {
              val context = LocalContext.current
              TrustVerificationScreen(
                  modifier = Modifier.safeDrawingPadding(),
                  onBack = { backStack.removeLastOrNull() },
                  onTokenScanned = { token -> 
                      try {
                          val uri = android.net.Uri.parse(token)
                          val pkBase64 = uri.getQueryParameter("pk")
                          val rToken = uri.getQueryParameter("rt")
                          val sigBase64 = uri.getQueryParameter("sig")
                          
                          if (pkBase64 != null && rToken != null && sigBase64 != null) {
                              val pkBytes = android.util.Base64.decode(pkBase64, android.util.Base64.URL_SAFE)
                              val sigBytes = android.util.Base64.decode(sigBase64, android.util.Base64.URL_SAFE)
                              val keyManager = app.clearspace.network.crypto.KeyManager(context)
                              val isValid = keyManager.verifySignature(rToken.toByteArray(Charsets.UTF_8), sigBytes, pkBytes)
                              
                              if (isValid) {
                                  val webRtcManager = app.clearspace.network.network.WebRtcManager.getInstance(context)
                                  webRtcManager.startCall(roomId = rToken, isCaller = false)
                                  
                                  val db = app.clearspace.network.storage.db.AetherDatabase.getPrivateDatabase()
                                  CoroutineScope(Dispatchers.IO).launch {
                                      db.peerDao().insert(
                                          app.clearspace.network.storage.db.entity.KnownPeer(
                                              publicKeyBase64 = pkBase64,
                                              alias = "Trusted Peer",
                                              avatarIndex = 0,
                                              onionAddress = null,
                                              trustLevel = app.clearspace.network.storage.db.entity.TrustLevel.TRUSTED_IN_PERSON
                                          )
                                      )
                                      withContext(Dispatchers.Main) {
                                          android.widget.Toast.makeText(context, "Trust Established!", android.widget.Toast.LENGTH_SHORT).show()
                                      }
                                  }
                              } else {
                                  android.widget.Toast.makeText(context, "Invalid Signature!", android.widget.Toast.LENGTH_SHORT).show()
                              }
                          }
                      } catch (e: Exception) {
                          android.widget.Toast.makeText(context, "Invalid QR Code", android.widget.Toast.LENGTH_SHORT).show()
                      }
                      backStack.removeLastOrNull() 
                  }
              )
            }
            entry<ChatRoute> { key ->
                app.clearspace.network.ui.chat.ChatScreen(
                    modifier = Modifier.safeDrawingPadding(),
                    peerPublicKey = key.peerKey,
                    peerAlias = key.peerAlias,
                    onNavigateBack = { backStack.removeLastOrNull() }
                )
            }
            entry<GlobalSearch> {
                app.clearspace.network.ui.social.SearchScreen(
                    modifier = Modifier.safeDrawingPadding(),
                    onNavigateBack = { backStack.removeLastOrNull() }
                )
            }
          },
      )
  }
}
}

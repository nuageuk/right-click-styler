package com.rightclickstyler;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.events.BeforeMenuRender;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ProfileChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = RightClickStylerPlugin.NAME
)
public class RightClickStylerPlugin extends Plugin
{
	static final String NAME = "Right Click Styler";
	private static final String INTERFACE_STYLES_GROUP = "interfaceStyles";
	private static final String HD_MENU_KEY = "hdMenu";
	private static final String MENU_ALPHA_KEY = "menuAlpha";
	// PluginManager stores each plugin's enabled state under its lowercased class name in the
	// "runelite" group. Interface Styles is off by default.
	private static final String INTERFACE_STYLES_PLUGIN_KEY = "interfacestylesplugin";
	private static final String INTERFACE_STYLES_WARNING = NAME
		+ ": turn off Interface Styles' High detail menu and set its Menu alpha to 255.";
	private static final int NAV_PRIORITY = 5;
	private static final boolean SUBMENU_PROTOTYPE =
		!"false".equals(System.getProperty("rightclickstyler.submenuPrototype"));
	private static final boolean SUBMENU_TRACE = Boolean.getBoolean("rightclickstyler.submenuTrace");
	private static final int MAX_ROUTE_DIAGNOSTICS = 64;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MenuOverlay menuOverlay;

	@Inject
	private ConfigManager configManager;

	@Inject
	private RightClickStylerConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private LocalPlayerSnapshot localPlayer;

	@Inject
	private ThemeManager themeManager;

	// Created in startUp() rather than injected: RuneLite installs its look and feel after plugins
	// load, and Swing components built before that keep the default light styling. Volatile because
	// ConfigChanged can arrive on any thread.
	private volatile RightClickStylerPanel panel;
	// Volatile because ConfigChanged can arrive on any thread.
	private volatile NavigationButton navigationButton;
	// Once per session, and only once it could actually be seen. Client thread only, apart from the
	// reset in shutDown.
	private volatile boolean warnedAboutInterfaceStylesConflict;
	private volatile boolean running;
	private boolean preparingPrototype;
	private int routeDiagnostics;
	private int lastRouteSignature;

	@Override
	protected void startUp()
	{
		// Reload before building the panel so enabling the plugin always uses the active profile.
		// Register the overlay only after the panel has been built successfully.
		themeManager.reload();
		panel = getInjector().getInstance(RightClickStylerPanel.class);
		navigationButton = NavigationButton.builder()
			.tooltip(NAME)
			.icon(ImageUtil.loadImageResource(RightClickStylerPlugin.class, "panel_icon.png"))
			.priority(NAV_PRIORITY)
			.panel(panel)
			.build();
		updateSidebarButton();

		menuOverlay.resetFailure();
		routeDiagnostics = 0;
		lastRouteSignature = 0;
		overlayManager.add(menuOverlay);

		updateInterfaceStylesWarning();
		running = true;
	}

	@Override
	protected void shutDown()
	{
		running = false;
		menuOverlay.clearPrepared();
		overlayManager.remove(menuOverlay);
		if (navigationButton != null)
		{
			clientToolbar.removeNavigation(navigationButton);
			navigationButton = null;
		}
		panel = null;
		warnedAboutInterfaceStylesConflict = false;
		localPlayer.setOnChange(null);
		localPlayer.clear();
	}

	@Subscribe
	public void onBeforeMenuRender(BeforeMenuRender event)
	{
		menuOverlay.setExternallyConsumed(event.isConsumed());
		if (event.isConsumed())
		{
			traceUnstyledEvent("already consumed");
			menuOverlay.discardPreparedAtEventStart();
			menuOverlay.invalidateSubmenuShadow();
			return;
		}
		if (SUBMENU_PROTOTYPE && handleSubmenuPrototype(event))
		{
			return;
		}
		if (menuOverlay.shouldDraw())
		{
			event.consume();
		}
		else
		{
			traceUnstyledEvent("plain renderer declined");
		}
	}

	/** Bounded, opt-in evidence for frames rejected before the submenu probe can report them. */
	private void traceUnstyledEvent(String reason)
	{
		if (!SUBMENU_TRACE || routeDiagnostics >= MAX_ROUTE_DIAGNOSTICS)
		{
			return;
		}
		try
		{
			if (!client.isMenuOpen())
			{
				return;
			}
			Menu root = client.getMenu();
			if (root == null)
			{
				return;
			}
			MenuEntry[] entries = root.getMenuEntries();
			int children = 0;
			if (entries != null)
			{
				for (MenuEntry entry : entries)
				{
					if (entry != null && entry.getSubMenu() != null)
					{
						children++;
					}
				}
			}
			boolean scrollable = client.isMenuScrollable();
			int scroll = scrollable ? client.getMenuScroll() : 0;
			int signature = reason.hashCode();
			signature = 31 * signature + (scrollable ? 1 : 0);
			signature = 31 * signature + scroll;
			signature = 31 * signature + (entries == null ? -1 : entries.length);
			signature = 31 * signature + children;
			signature = 31 * signature + root.getMenuWidth();
			signature = 31 * signature + root.getMenuHeight();
			if (signature == lastRouteSignature)
			{
				return;
			}
			lastRouteSignature = signature;
			routeDiagnostics++;
			log.info("Menu render route skipped reason={} prototype={} scrollable={} scroll={} entries={} children={} root=({},{} {}x{}) diagnostic={}/{}",
				reason, SUBMENU_PROTOTYPE, scrollable, scroll, entries == null ? -1 : entries.length,
				children, root.getMenuX(), root.getMenuY(), root.getMenuWidth(), root.getMenuHeight(),
				routeDiagnostics, MAX_ROUTE_DIAGNOSTICS);
		}
		catch (RuntimeException ignored)
		{
			// Diagnostics must never affect native fallback or event ownership.
		}
	}

	/** @return true when the opt-in submenu prototype owns this event. */
	boolean handleSubmenuPrototype(BeforeMenuRender event)
	{
		if (preparingPrototype)
		{
			menuOverlay.recordReentrantEvent();
			return true;
		}
		// The only mouse read for this frame. getMouseCanvasPosition follows the AWT thread
		// live, while native submenu state is fixed for the cycle, so preparation and the
		// final draw both use this sample instead of re-reading the pointer.
		Point frameMouse = client.getMouseCanvasPosition();
		menuOverlay.discardPreparedAtEventStart();
		if (!client.isMenuOpen())
		{
			menuOverlay.invalidateSubmenuShadow();
			return false;
		}
		Menu root = client.getMenu();
		MenuEntry[] entries = root == null ? null : root.getMenuEntries();
		if (entries == null
			|| !(MenuOverlay.containsSubMenu(entries) || client.isMenuScrollable()))
		{
			menuOverlay.invalidateSubmenuShadow();
			return false;
		}
		if (event.isConsumed())
		{
			menuOverlay.invalidateSubmenuShadow();
			menuOverlay.recordAlreadyConsumedSubmenu();
			return true;
		}
		preparingPrototype = true;
		try
		{
			if (menuOverlay.prepareSubmenuFrame(root, entries, frameMouse))
			{
				// A late structural change or overlay draw failure can leave this
				// consumed frame blank. Only a later menu event can choose
				// native fallback; this frame cannot be restored.
				event.consume();
				menuOverlay.recordSubmenuConsumed();
			}
		}
		finally
		{
			preparingPrototype = false;
		}
		return true;
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		menuOverlay.menuOpened();
	}

	// Polled each tick so level-ups reach the preview; this also covers login, where the local player
	// is often still null on the LOGGED_IN event.
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (SUBMENU_PROTOTYPE && Boolean.getBoolean("rightclickstyler.submenuTrace"))
		{
			menuOverlay.reportPrototype();
		}
		// Diagnostic, opt-in; independent of the prototype so both modes can be measured.
		menuOverlay.reportPerf();
		Player player = client.getLocalPlayer();
		if (player != null)
		{
			localPlayer.set(player.getCombatLevel());
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			localPlayer.clear();
		}
		else if (event.getGameState() == GameState.LOGGED_IN)
		{
			warnInChatOnce();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (RightClickStylerConfig.GROUP.equals(event.getGroup())
			&& RightClickStylerConfig.KEY_SHOW_SIDEBAR_BUTTON.equals(event.getKey()))
		{
			updateSidebarButton();
			return;
		}
		if ((INTERFACE_STYLES_GROUP.equals(event.getGroup())
			&& (HD_MENU_KEY.equals(event.getKey()) || MENU_ALPHA_KEY.equals(event.getKey())))
			|| (RuneLiteConfig.GROUP_NAME.equals(event.getGroup()) && INTERFACE_STYLES_PLUGIN_KEY.equals(event.getKey())))
		{
			updateInterfaceStylesWarning();
		}
	}

	@Subscribe
	public void onProfileChanged(ProfileChanged event)
	{
		if (!running)
		{
			return;
		}
		RightClickStylerPanel expectedPanel = panel;
		SwingUtilities.invokeLater(() ->
		{
			if (!running || panel != expectedPanel)
			{
				return;
			}
			themeManager.reload();
			RightClickStylerPanel current = panel;
			if (current != null)
			{
				current.reloadFromConfig();
			}
			updateSidebarButton();
			updateInterfaceStylesWarning();
		});
	}

	@Override
	public void resetConfiguration()
	{
		RightClickStylerPanel expectedPanel = panel;
		Runnable reload = () ->
		{
			if (expectedPanel == null)
			{
				if (!running)
				{
					themeManager.reload();
				}
				return;
			}
			if (!running || panel != expectedPanel)
			{
				return;
			}
			themeManager.reload();
			expectedPanel.reloadFromConfig();
			updateSidebarButton();
			updateInterfaceStylesWarning();
		};

		if (SwingUtilities.isEventDispatchThread())
		{
			reload.run();
		}
		else
		{
			SwingUtilities.invokeLater(reload);
		}
	}

	@Provides
	RightClickStylerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RightClickStylerConfig.class);
	}

	// ClientToolbar hands both calls to the EDT, and adding or removing twice is harmless.
	private void updateSidebarButton()
	{
		NavigationButton button = navigationButton;
		if (button == null)
		{
			return;
		}
		if (config.showSidebarButton())
		{
			clientToolbar.addNavigation(button);
		}
		else
		{
			clientToolbar.removeNavigation(button);
		}
	}

	/**
	 * Interface Styles conflicts while enabled with its high-detail menu on or its menu alpha not 255.
	 */
	private boolean isInterfaceStylesConflictActive()
	{
		if (!Boolean.parseBoolean(configManager.getConfiguration(RuneLiteConfig.GROUP_NAME, INTERFACE_STYLES_PLUGIN_KEY)))
		{
			return false;
		}
		if (Boolean.parseBoolean(configManager.getConfiguration(INTERFACE_STYLES_GROUP, HD_MENU_KEY)))
		{
			return true;
		}
		Integer menuAlpha = configManager.getConfiguration(INTERFACE_STYLES_GROUP, MENU_ALPHA_KEY, Integer.class);
		return menuAlpha != null && menuAlpha != 255;
	}

	// Called from startUp and ConfigChanged, neither of which is guaranteed to be on the EDT or the
	// client thread.
	private void updateInterfaceStylesWarning()
	{
		boolean active = isInterfaceStylesConflictActive();
		RightClickStylerPanel current = panel;
		if (current != null)
		{
			SwingUtilities.invokeLater(() -> current.setInterfaceStylesWarningVisible(active));
		}
		if (active)
		{
			clientThread.invokeLater(this::warnInChatOnce);
		}
	}

	// Client thread only. Skipped until logged in, so the one warning isn't spent where nobody sees it.
	private void warnInChatOnce()
	{
		if (warnedAboutInterfaceStylesConflict || client.getGameState() != GameState.LOGGED_IN
			|| !isInterfaceStylesConflictActive())
		{
			return;
		}
		warnedAboutInterfaceStylesConflict = true;
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", INTERFACE_STYLES_WARNING, null);
	}
}

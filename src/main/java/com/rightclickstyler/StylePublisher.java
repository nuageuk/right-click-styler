package com.rightclickstyler;

import java.awt.Color;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.events.ClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;

@Singleton
final class StylePublisher
{
	static final String NAMESPACE = "right-click-styler";
	private final EventBus eventBus;
	private final ClientThread clientThread;
	private final ThemeManager themes;
	private volatile boolean active;
	private Theme lastSent;

	@Inject
	StylePublisher(EventBus eventBus, ClientThread clientThread, ThemeManager themes)
	{
		this.eventBus = eventBus;
		this.clientThread = clientThread;
		this.themes = themes;
	}

	void start()
	{
		active = true;
		eventBus.register(this);
		clientThread.invokeLater(this::publish);
	}

	void stop()
	{
		active = false;
		eventBus.unregister(this);
		clientThread.invokeLater(() ->
		{
			if (!active)
			{
				lastSent = null;
				eventBus.post(new PluginMessage(NAMESPACE, "style", Collections.singletonMap("active", false)));
			}
		});
	}

	@Subscribe
	public void onClientTick(ClientTick event)
	{
		if (active && themes.effectiveTheme() != lastSent)
		{
			publish();
		}
	}

	@Subscribe
	public void onPluginMessage(PluginMessage event)
	{
		if (NAMESPACE.equals(event.getNamespace()) && "style-request".equals(event.getName()))
		{
			clientThread.invokeLater(this::publish);
		}
	}

	private void publish()
	{
		if (!active)
		{
			return;
		}
		Theme theme = themes.effectiveTheme();
		lastSent = theme;
		eventBus.post(new PluginMessage(NAMESPACE, "style", snapshot(theme)));
	}

	static Map<String, Object> snapshot(Theme theme)
	{
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("version", 1);
		values.put("active", true);
		values.put("font", MenuRenderer.buildFont(theme.font, theme.fontSize));
		values.put("text", theme.text);
		values.put("background", new Color(theme.bg.getRed(), theme.bg.getGreen(), theme.bg.getBlue(),
			(int) Math.round(theme.opacity * 255d / 100d)));
		values.put("border", theme.border);
		values.put("radius", theme.radius);
		values.put("showBorder", theme.showBorder);
		values.put("showShadow", theme.showShadow);
		values.put("textShadow", theme.textShadow);
		return Collections.unmodifiableMap(values);
	}
}

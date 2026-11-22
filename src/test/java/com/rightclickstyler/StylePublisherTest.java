package com.rightclickstyler;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.events.ClientTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class StylePublisherTest
{
	@Test
	public void publishesOnClientQueueAtStartupOnRequestAndAfterThemeChange()
	{
		Fixture f = new Fixture();
		f.publisher.start();
		assertTrue(f.received.values.isEmpty());
		f.drain();
		assertEquals(1, f.received.values.size());
		for (int tick = 0; tick < 100; tick++)
			f.bus.post(new ClientTick());
		assertEquals(1, f.received.values.size());
		f.bus.post(new PluginMessage(StylePublisher.NAMESPACE, "style-request"));
		assertEquals(1, f.received.values.size());
		f.drain();
		assertEquals(2, f.received.values.size());
		when(f.themes.effectiveTheme()).thenReturn(f.theme.withText(Color.MAGENTA));
		f.bus.post(new ClientTick());
		assertEquals(Color.MAGENTA, f.received.values.get(2).getData().get("text"));
	}

	@Test
	public void shutdownCancelsPendingPublicationAndReportsInactive()
	{
		Fixture f = new Fixture();
		f.publisher.start();
		f.publisher.stop();
		f.drain();
		assertEquals(1, f.received.values.size());
		Map<String, Object> inactive = f.received.values.get(0).getData();
		assertEquals(2, inactive.size());
		assertEquals(Integer.valueOf(1), inactive.get("version"));
		assertEquals(Boolean.FALSE, inactive.get("active"));
		assertThrows(UnsupportedOperationException.class, () -> inactive.put("active", true));
		f.bus.post(new ClientTick());
		f.bus.post(new PluginMessage(StylePublisher.NAMESPACE, "style-request"));
		f.drain();
		assertEquals(1, f.received.values.size());
		f.publisher.start();
		f.drain();
		assertEquals(true, f.received.values.get(1).getData().get("active"));
	}

	public static class Received
	{
		final List<PluginMessage> values = new ArrayList<>();
		@Subscribe
		public void onPluginMessage(PluginMessage message)
		{
			if ("style".equals(message.getName()))
				values.add(message);
		}
	}

	private static class Fixture
	{
		final EventBus bus = new EventBus(error -> { throw new AssertionError(error); });
		final ClientThread clientThread = mock(ClientThread.class);
		final ThemeManager themes = mock(ThemeManager.class);
		final Theme theme = BuiltInThemes.ALL.get(0);
		final Received received = new Received();
		final List<Runnable> queue = new ArrayList<>();
		final StylePublisher publisher = new StylePublisher(bus, clientThread, themes);

		Fixture()
		{
			bus.register(received);
			when(themes.effectiveTheme()).thenReturn(theme);
			doAnswer(call -> { queue.add(call.getArgument(0)); return null; })
				.when(clientThread).invokeLater(any(Runnable.class));
		}

		void drain()
		{
			while (!queue.isEmpty())
				queue.remove(0).run();
		}
	}
}

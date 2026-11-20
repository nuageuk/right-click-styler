package com.rightclickstyler;

import com.google.gson.Gson;
import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.util.ColorUtil;

/**
 * An isolated, in-memory stand-in for RuneLite's profile-scoped configuration. Nothing here
 * touches the filesystem beyond reading test resources, and never the user's {@code .runelite}.
 * <p>
 * Values are stored as the strings RuneLite's {@code ConfigManager} stores, keyed by whole key
 * ({@code group.key}), and {@link #config()} converts them back the way RuneLite's config proxy
 * does: {@code ColorUtil.fromString} for colours, {@code Enum.valueOf}, {@code parseInt},
 * {@code parseBoolean}, and the interface default when a key is unset or unreadable.
 */
final class RuneLiteProfileStore
{
	private static final String PREFIX = RightClickStylerConfig.GROUP + ".";

	private final Map<String, Map<String, String>> profiles = new LinkedHashMap<>();
	private final List<String> writeLog = new ArrayList<>();
	private String current;
	private final RightClickStylerConfig config = createConfig();

	/** Loads {@code released/released-profile-<name>.properties} for each name; the first is active. */
	static RuneLiteProfileStore released(String... names) throws IOException
	{
		RuneLiteProfileStore store = new RuneLiteProfileStore();
		for (String name : names)
		{
			Properties properties = new Properties();
			try (InputStream in = RuneLiteProfileStore.class.getResourceAsStream(
				"released/released-profile-" + name + ".properties"))
			{
				if (in == null)
				{
					throw new IOException("missing fixture for profile " + name);
				}
				properties.load(in);
			}
			Map<String, String> values = new TreeMap<>();
			for (String key : properties.stringPropertyNames())
			{
				values.put(key, properties.getProperty(key));
			}
			store.profiles.put(name, values);
		}
		store.current = names.length > 0 ? names[0] : null;
		return store;
	}

	static RuneLiteProfileStore empty(String name)
	{
		RuneLiteProfileStore store = new RuneLiteProfileStore();
		store.profiles.put(name, new TreeMap<>());
		store.current = name;
		return store;
	}

	/** Like RuneLite switching profile: later reads and writes use the other profile only. */
	void switchTo(String name)
	{
		profiles.computeIfAbsent(name, k -> new TreeMap<>());
		current = name;
	}

	String currentProfile()
	{
		return current;
	}

	/** {@code ConfigManager.getConfiguration(group, key)}. */
	String get(String key)
	{
		return profiles.get(current).get(PREFIX + key);
	}

	/** {@code ConfigManager.setConfiguration(group, key, String)}. */
	void setRaw(String key, String value)
	{
		writeLog.add(current + ":set:" + key);
		profiles.get(current).put(PREFIX + key, value);
	}

	/** {@code ConfigManager.setConfiguration(group, key, Object)}, via its objectToString. */
	void set(String key, Object value)
	{
		String s;
		if (value instanceof Color)
		{
			s = String.valueOf(((Color) value).getRGB());
		}
		else if (value instanceof Enum)
		{
			s = ((Enum<?>) value).name();
		}
		else
		{
			s = String.valueOf(value);
		}
		setRaw(key, s);
	}

	/** {@code ConfigManager.unsetConfiguration(group, key)}. */
	void unset(String key)
	{
		writeLog.add(current + ":unset:" + key);
		profiles.get(current).remove(PREFIX + key);
	}

	/** A copy of one profile's stored strings, for byte-for-byte comparisons. */
	Map<String, String> snapshot(String name)
	{
		return new TreeMap<>(profiles.get(name));
	}

	List<String> writes()
	{
		return new ArrayList<>(writeLog);
	}

	void clearWrites()
	{
		writeLog.clear();
	}

	RightClickStylerConfig config()
	{
		return config;
	}

	/** A ThemeManager bound to this store, as the injected one is bound to ConfigManager. */
	ThemeManager themeManager()
	{
		return new ThemeManager(() -> get(RightClickStylerConfig.KEY_USER_THEMES), this::setRaw, config, new Gson());
	}

	private RightClickStylerConfig createConfig()
	{
		return (RightClickStylerConfig) Proxy.newProxyInstance(RightClickStylerConfig.class.getClassLoader(),
			new Class<?>[]{RightClickStylerConfig.class}, (proxy, method, args) ->
			{
				ConfigItem item = method.getAnnotation(ConfigItem.class);
				if (item == null)
				{
					switch (method.getName())
					{
						case "hashCode": return System.identityHashCode(proxy);
						case "equals": return proxy == args[0];
						case "toString": return "RuneLiteProfileStore.config";
						default: throw new UnsupportedOperationException(method.getName());
					}
				}
				String raw = get(item.keyName());
				if (raw != null)
				{
					try
					{
						return convert(raw, method.getReturnType());
					}
					catch (RuntimeException e)
					{
						// RuneLite logs and falls back to the default for unreadable values.
					}
				}
				return defaultValue(proxy, method);
			});
	}

	private static Object convert(String raw, Class<?> type)
	{
		if (type == boolean.class)
		{
			return Boolean.parseBoolean(raw);
		}
		if (type == int.class)
		{
			return Integer.parseInt(raw);
		}
		if (type == Color.class)
		{
			Color colour = ColorUtil.fromString(raw);
			if (colour == null)
			{
				throw new IllegalArgumentException(raw);
			}
			return colour;
		}
		if (type.isEnum())
		{
			return enumValue(type, raw);
		}
		if (type == String.class)
		{
			return raw;
		}
		throw new IllegalArgumentException("unsupported config type " + type);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object enumValue(Class<?> type, String raw)
	{
		return Enum.valueOf((Class) type, raw);
	}

	static Object defaultValue(Object proxy, Method method) throws Throwable
	{
		return MethodHandles.privateLookupIn(RightClickStylerConfig.class, MethodHandles.lookup())
			.unreflectSpecial(method, RightClickStylerConfig.class)
			.bindTo(proxy)
			.invokeWithArguments();
	}
}

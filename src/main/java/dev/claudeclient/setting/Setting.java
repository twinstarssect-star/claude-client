package dev.claudeclient.setting;

import com.google.gson.JsonElement;

public abstract class Setting<T> {
	private final String name;
	private final String description;
	private final T defaultValue;
	protected T value;

	protected Setting(String name, String description, T defaultValue) {
		this.name = name;
		this.description = description;
		this.defaultValue = defaultValue;
		this.value = defaultValue;
	}

	public String name() {
		return name;
	}

	public String description() {
		return description;
	}

	public T get() {
		return value;
	}

	public void set(T value) {
		this.value = value;
	}

	public void reset() {
		this.value = defaultValue;
	}

	public abstract JsonElement toJson();

	/** Loads a saved value; anything unreadable leaves the current value alone. */
	public abstract void fromJson(JsonElement json);
}

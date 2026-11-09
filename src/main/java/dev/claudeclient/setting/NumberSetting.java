package dev.claudeclient.setting;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;

/** A number with a range and step, shown as a slider. */
public final class NumberSetting extends Setting<Double> {
	private final double min, max, step;
	private final String unit;

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step, String unit) {
		super(name, description, defaultValue);
		this.min = min;
		this.max = max;
		this.step = step;
		this.unit = unit == null ? "" : unit;
	}

	public NumberSetting(String name, String description, double defaultValue, double min, double max, double step) {
		this(name, description, defaultValue, min, max, step, "");
	}

	public double min() {
		return min;
	}

	public double max() {
		return max;
	}

	public int getInt() {
		return (int) Math.round(value);
	}

	public float getFloat() {
		return value.floatValue();
	}

	@Override
	public void set(Double v) {
		double clamped = Math.max(min, Math.min(max, v));
		value = step > 0 ? Math.round((clamped - min) / step) * step + min : clamped;
		value = Math.max(min, Math.min(max, value));
	}

	/** 0..1 position of the value on the slider. */
	public double fraction() {
		return max == min ? 0 : (value - min) / (max - min);
	}

	public void setFraction(double f) {
		set(min + Math.max(0, Math.min(1, f)) * (max - min));
	}

	public String display() {
		boolean whole = step >= 1 && Math.abs(value - Math.rint(value)) < 1e-9;
		String v = whole ? Long.toString(Math.round(value)) : String.format(java.util.Locale.ROOT, step >= 0.1 ? "%.1f" : "%.2f", value);
		return v + unit;
	}

	@Override
	public JsonElement toJson() {
		return new JsonPrimitive(value);
	}

	@Override
	public void fromJson(JsonElement json) {
		if (json != null && json.isJsonPrimitive() && json.getAsJsonPrimitive().isNumber()) set(json.getAsDouble());
	}
}

package dev.claudeclient.render;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Collects boxes, lines and labels from modules during one frame and draws them all at once through walls (no depth
 * test): filled quads first, outlines on top, then labels.
 * <p>
 * Anything farther than the camera's far clip plane would vanish, so it's pulled in along the same line of sight and
 * shrunk by the same factor: it looks identical on screen but never gets clipped, at any distance.
 */
public final class Render3D {
	private static final RenderPipeline LINES_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.LINES_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath("claudeclient", "pipeline/esp_lines"))
					.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
					.withDepthWrite(false)
					.build());
	private static final RenderPipeline QUADS_PIPELINE = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath("claudeclient", "pipeline/esp_quads"))
					.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
					.withDepthWrite(false)
					.withCull(false)
					.build());
	private static final RenderType LINES = RenderType.create("claudeclient_esp_lines", RenderSetup.builder(LINES_PIPELINE).createRenderSetup());
	private static final RenderType QUADS = RenderType.create("claudeclient_esp_quads", RenderSetup.builder(QUADS_PIPELINE).sortOnUpload().createRenderSetup());

	private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 20));

	/** Growable flat buffer: {@code stride} floats and one colour per item. */
	private static final class Buf {
		final int stride;
		float[] f = new float[1 << 16];
		int[] c = new int[1 << 14];
		int n;

		Buf(int stride) {
			this.stride = stride;
		}

		int next() {
			int i = n * stride;
			if (i + stride > f.length) f = Arrays.copyOf(f, f.length * 2);
			if (n + 1 > c.length) c = Arrays.copyOf(c, c.length * 2);
			return i;
		}
	}

	private static final Buf LINE_BUF = new Buf(7), QUAD_BUF = new Buf(12);

	private record Label(float x, float y, float z, String[] lines, int[] colors, float scale) {}

	private static final List<Label> LABELS = new ArrayList<>();
	private static final Vector3f SCRATCH = new Vector3f();
	private static final Vector3f NORMAL = new Vector3f();

	private final Vec3 camera;
	private final Quaternionf orientation;
	private final float tickDelta;
	private final float maxDist;
	private final float forwardX, forwardY, forwardZ;
	private float lineWidth = 2f;

	public Render3D(Vec3 camera, Quaternionf orientation, float tickDelta) {
		this.camera = camera;
		this.orientation = orientation;
		this.tickDelta = tickDelta;
		this.maxDist = Math.max(96f, Minecraft.getInstance().gameRenderer.getDepthFar() * 0.8f);
		Vector3f v = SCRATCH.set(0, 0, -1).rotate(orientation);
		forwardX = v.x;
		forwardY = v.y;
		forwardZ = v.z;
	}

	public Vec3 camera() {
		return camera;
	}

	public float tickDelta() {
		return tickDelta;
	}

	public void setLineWidth(float width) {
		this.lineWidth = width;
	}

	/** Distance from the camera to a point. */
	public double distance(double x, double y, double z) {
		double dx = x - camera.x, dy = y - camera.y, dz = z - camera.z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	// ---------------------------------------------------------------- boxes

	/** Outline and/or fill a box. Pass 0 for a colour to skip that part. */
	public void box(AABB b, int fillColor, int lineColor) {
		box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, fillColor, lineColor);
	}

	public void box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int fillColor, int lineColor) {
		float x1 = (float) (minX - camera.x), y1 = (float) (minY - camera.y), z1 = (float) (minZ - camera.z);
		float x2 = (float) (maxX - camera.x), y2 = (float) (maxY - camera.y), z2 = (float) (maxZ - camera.z);
		float cx = (x1 + x2) / 2, cy = (y1 + y2) / 2, cz = (z1 + z2) / 2;
		float d = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
		if (d > maxDist) {
			float k = maxDist / d;
			x1 *= k;
			y1 *= k;
			z1 *= k;
			x2 *= k;
			y2 *= k;
			z2 *= k;
		}
		boxRel(x1, y1, z1, x2, y2, z2, fillColor, lineColor);
	}

	public void block(int x, int y, int z, int fillColor, int lineColor) {
		box(x, y, z, x + 1, y + 1, z + 1, fillColor, lineColor);
	}

	/** An entity's bounding box at its interpolated position for this frame. */
	public AABB entityBox(Entity entity, double expand) {
		Vec3 lerped = entity.getPosition(tickDelta);
		return entity.getBoundingBox().move(lerped.subtract(entity.position())).inflate(expand);
	}

	private void boxRel(float x1, float y1, float z1, float x2, float y2, float z2, int fill, int line) {
		if ((fill >>> 24) != 0) {
			quad(fill, x1, y1, z1, x2, y1, z1, x2, y1, z2, x1, y1, z2);
			quad(fill, x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1);
			quad(fill, x1, y1, z1, x1, y2, z1, x2, y2, z1, x2, y1, z1);
			quad(fill, x1, y1, z2, x2, y1, z2, x2, y2, z2, x1, y2, z2);
			quad(fill, x1, y1, z1, x1, y1, z2, x1, y2, z2, x1, y2, z1);
			quad(fill, x2, y1, z1, x2, y2, z1, x2, y2, z2, x2, y1, z2);
		}
		if ((line >>> 24) != 0) {
			rawLine(x1, y1, z1, x2, y1, z1, line);
			rawLine(x2, y1, z1, x2, y1, z2, line);
			rawLine(x2, y1, z2, x1, y1, z2, line);
			rawLine(x1, y1, z2, x1, y1, z1, line);
			rawLine(x1, y2, z1, x2, y2, z1, line);
			rawLine(x2, y2, z1, x2, y2, z2, line);
			rawLine(x2, y2, z2, x1, y2, z2, line);
			rawLine(x1, y2, z2, x1, y2, z1, line);
			rawLine(x1, y1, z1, x1, y2, z1, line);
			rawLine(x2, y1, z1, x2, y2, z1, line);
			rawLine(x2, y1, z2, x2, y2, z2, line);
			rawLine(x1, y1, z2, x1, y2, z2, line);
		}
	}

	/** A see-through quad with camera-relative corners (no far clamp), for dense overlays near the camera. */
	public void quadRel(int color, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
			float x4, float y4, float z4) {
		if ((color >>> 24) != 0) quad(color, x1, y1, z1, x2, y2, z2, x3, y3, z3, x4, y4, z4);
	}

	// ---------------------------------------------------------------- lines

	public void line(Vec3 from, Vec3 to, int color) {
		float x1 = (float) (from.x - camera.x), y1 = (float) (from.y - camera.y), z1 = (float) (from.z - camera.z);
		float x2 = (float) (to.x - camera.x), y2 = (float) (to.y - camera.y), z2 = (float) (to.z - camera.z);
		float d = (float) Math.sqrt(Math.max(x1 * x1 + y1 * y1 + z1 * z1, x2 * x2 + y2 * y2 + z2 * z2));
		if (d > maxDist) {
			float k = maxDist / d;
			x1 *= k;
			y1 *= k;
			z1 *= k;
			x2 *= k;
			y2 *= k;
			z2 *= k;
		}
		rawLine(x1, y1, z1, x2, y2, z2, color);
	}

	/** A line from just in front of the crosshair toward a point, pulled in if it's beyond the far plane. */
	public void tracer(double tx, double ty, double tz, int color) {
		float dx = (float) (tx - camera.x), dy = (float) (ty - camera.y), dz = (float) (tz - camera.z);
		float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len > maxDist) {
			float k = maxDist / len;
			dx *= k;
			dy *= k;
			dz *= k;
		}
		rawLine(forwardX, forwardY, forwardZ, dx, dy, dz, color);
	}

	private void rawLine(float x1, float y1, float z1, float x2, float y2, float z2, int color) {
		Buf b = LINE_BUF;
		int i = b.next();
		float[] f = b.f;
		f[i] = x1;
		f[i + 1] = y1;
		f[i + 2] = z1;
		f[i + 3] = x2;
		f[i + 4] = y2;
		f[i + 5] = z2;
		f[i + 6] = lineWidth;
		b.c[b.n++] = color;
	}

	private static void quad(int color, float x1, float y1, float z1, float x2, float y2, float z2, float x3, float y3, float z3,
			float x4, float y4, float z4) {
		Buf b = QUAD_BUF;
		int i = b.next();
		float[] f = b.f;
		f[i] = x1;
		f[i + 1] = y1;
		f[i + 2] = z1;
		f[i + 3] = x2;
		f[i + 4] = y2;
		f[i + 5] = z2;
		f[i + 6] = x3;
		f[i + 7] = y3;
		f[i + 8] = z3;
		f[i + 9] = x4;
		f[i + 10] = y4;
		f[i + 11] = z4;
		b.c[b.n++] = color;
	}

	// ---------------------------------------------------------------- text

	/** Camera-facing text at a world position, through walls. It grows with distance so it stays readable far away. */
	public void text(double x, double y, double z, String[] lines, int[] colors, float scale) {
		float rx = (float) (x - camera.x), ry = (float) (y - camera.y), rz = (float) (z - camera.z);
		float d = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
		if (d > maxDist) {
			float k = maxDist / d;
			rx *= k;
			ry *= k;
			rz *= k;
		}
		LABELS.add(new Label(rx, ry, rz, lines, colors, scale));
	}

	public void text(double x, double y, double z, String line, int color, float scale) {
		text(x, y, z, new String[]{line}, new int[]{color}, scale);
	}

	// ---------------------------------------------------------------- flush

	/** Draw everything collected this frame. */
	public void flush(PoseStack poseStack) {
		if (QUAD_BUF.n == 0 && LINE_BUF.n == 0 && LABELS.isEmpty()) return;
		PoseStack.Pose pose = poseStack.last();
		drawQuads(pose);
		drawLines(pose);
		if (!LABELS.isEmpty()) {
			Font font = Minecraft.getInstance().font;
			for (Label label : LABELS) {
				float d = (float) Math.sqrt(label.x * label.x + label.y * label.y + label.z * label.z);
				float s = 0.025f * label.scale * Math.max(1f, d / 10f);
				Matrix4f m = new Matrix4f(pose.pose()).translate(label.x, label.y, label.z).rotate(orientation).scale(s, -s, s);
				float y = -label.lines.length * 10 / 2f;
				for (int i = 0; i < label.lines.length; i++) {
					String line = label.lines[i];
					int color = label.colors[Math.min(i, label.colors.length - 1)];
					font.drawInBatch(line, -font.width(line) / 2f, y, color, false, m, BUFFERS,
							Font.DisplayMode.SEE_THROUGH, i == 0 ? 0xA0161514 : 0x80161514, LightTexture.FULL_BRIGHT);
					y += 10;
				}
			}
			BUFFERS.endBatch();
		}
		QUAD_BUF.n = 0;
		LINE_BUF.n = 0;
		LABELS.clear();
	}

	private static boolean identity(PoseStack.Pose pose) {
		return (pose.pose().properties() & Matrix4fc.PROPERTY_IDENTITY) != 0;
	}

	private static VertexConsumer vertex(VertexConsumer buf, Matrix4f m, boolean ident, float x, float y, float z) {
		if (ident) return buf.addVertex(x, y, z);
		Vector3f v = m.transformPosition(x, y, z, SCRATCH);
		return buf.addVertex(v.x, v.y, v.z);
	}

	private static void drawQuads(PoseStack.Pose pose) {
		Buf b = QUAD_BUF;
		if (b.n == 0) return;
		VertexConsumer buf = BUFFERS.getBuffer(QUADS);
		Matrix4f m = pose.pose();
		boolean ident = identity(pose);
		float[] f = b.f;
		for (int q = 0, i = 0; q < b.n; q++, i += 12) {
			int color = b.c[q];
			vertex(buf, m, ident, f[i], f[i + 1], f[i + 2]).setColor(color);
			vertex(buf, m, ident, f[i + 3], f[i + 4], f[i + 5]).setColor(color);
			vertex(buf, m, ident, f[i + 6], f[i + 7], f[i + 8]).setColor(color);
			vertex(buf, m, ident, f[i + 9], f[i + 10], f[i + 11]).setColor(color);
		}
		BUFFERS.endBatch(QUADS);
	}

	private static void drawLines(PoseStack.Pose pose) {
		Buf b = LINE_BUF;
		if (b.n == 0) return;
		VertexConsumer buf = BUFFERS.getBuffer(LINES);
		Matrix4f m = pose.pose();
		boolean ident = identity(pose);
		Vector3f normal = NORMAL;
		float[] f = b.f;
		for (int l = 0, i = 0; l < b.n; l++, i += 7) {
			float x1 = f[i], y1 = f[i + 1], z1 = f[i + 2], x2 = f[i + 3], y2 = f[i + 4], z2 = f[i + 5], width = f[i + 6];
			normal.set(x2 - x1, y2 - y1, z2 - z1);
			if (normal.lengthSquared() < 1e-8f) continue;
			normal.normalize();
			if (!ident) pose.transformNormal(normal.x, normal.y, normal.z, normal);
			int color = b.c[l];
			vertex(buf, m, ident, x1, y1, z1).setColor(color).setNormal(normal.x, normal.y, normal.z).setLineWidth(width);
			vertex(buf, m, ident, x2, y2, z2).setColor(color).setNormal(normal.x, normal.y, normal.z).setLineWidth(width);
		}
		BUFFERS.endBatch(LINES);
	}

	public static float partialTick() {
		return Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(true);
	}

	/** ARGB with a new alpha. */
	public static int alpha(int argb, int a) {
		return (a << 24) | (argb & 0xFFFFFF);
	}
}

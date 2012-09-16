package se.embargo.retroboy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import se.embargo.core.graphics.Bitmaps;
import se.embargo.retroboy.filter.AtkinsonFilter;
import se.embargo.retroboy.filter.BayerFilter;
import se.embargo.retroboy.filter.HalftoneFilter;
import se.embargo.retroboy.filter.IImageFilter;
import se.embargo.retroboy.filter.PaletteFilter;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.Camera;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

public class Pictures {
	private static final String TAG = "Pictures";

	public static final String PREFS_NAMESPACE = "se.embargo.retroboy";
	
	public static final String PREF_FILTER = "filter";
	
	private static final String PREF_FILTER_GAMEBOY_CAMERA = "nintendo_gameboy_camera";
	private static final String PREF_FILTER_GAMEBOY_SCREEN = "nintendo_gameboy_screen";
	private static final String PREF_FILTER_COMMODORE_64 = "commodore_64";
	private static final String PREF_FILTER_ATKINSON = "atkinson";
	private static final String PREF_FILTER_HALFTONE = "halftone";
	public static final String PREF_FILTER_DEFAULT = PREF_FILTER_GAMEBOY_CAMERA;

	public static final String PREF_CONTRAST = "contrast";
	public static final String PREF_CONTRAST_DEFAULT = "0";

	public static final String PREF_RESOLUTION = "resolution";
	public static final String PREF_RESOLUTION_DEFAULT = "480x360";
	
	private static final String PREF_IMAGECOUNT = "imagecount";
	
	private static final String DIRECTORY = "Retroboy";
	private static final String FILENAME = "IMGR%04d.png";

	public static class Resolution {
		public final int width, height;
		
		public Resolution(int width, int height) {
			this.width = width;
			this.height = height;
		}
		
		@Override
		public String toString() {
			return width + "x" + height;
		}
	}
	
	/**
	 * @return	The directory where images are stored
	 */
	public static File getStorageDirectory() {
		File path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
		return new File(path + "/" + DIRECTORY);
	}
	
	/**
	 * Get the contrast adjustment from preferences
	 * @param prefs	Preferences to get the contrast from
	 * @return		The selected contrast adjustment, [-100, 100]
	 */
	public static int getContrast(SharedPreferences prefs) {
		String contrast = prefs.getString(Pictures.PREF_CONTRAST, Pictures.PREF_CONTRAST_DEFAULT);
		try {
			return Integer.parseInt(contrast);
		}
		catch (NumberFormatException e) {}
		
		Log.w(TAG, "Failed to parse contrast preference " + contrast);
		return 0;
	}

	/**
	 * Get the preview resolution
	 * @param prefs	Preferences to get the resolution from
	 * @return		The selected preview resultion
	 */
	public static Resolution getResolution(SharedPreferences prefs) {
		String resolution = prefs.getString(Pictures.PREF_RESOLUTION, Pictures.PREF_RESOLUTION_DEFAULT);
		String[] components = resolution.split("x");
		
		if (components.length == 2) {
			try {
				int width = Integer.parseInt(components[0]),
					height = Integer.parseInt(components[1]);
				return new Resolution(width, height);
			}
			catch (NumberFormatException e) {}
		}
		
		Log.w(TAG, "Failed to parse resolution " + resolution);
		return new Resolution(480, 360);
	}
	
	public static File compress(Context context, String inputname, String outputpath, Bitmap bm) {
		SharedPreferences prefs = context.getSharedPreferences(PREFS_NAMESPACE, Context.MODE_PRIVATE);

		// Create path to output file
		File file;
		if (outputpath != null) {
			// Overwrite the previously processed file
			file = new File(outputpath);
		}
		else {
			String filename;
			boolean autogenerated = false;
			
			do {
				if (inputname != null) {
					// Use the original image name
					filename = new File(inputname).getName();
					filename = filename.split("\\.", 2)[0];
					filename += ".png";
				}
				else {
					// Create a new sequential name
					autogenerated = true;
					
					int count = prefs.getInt(PREF_IMAGECOUNT, 0);
					filename = String.format(FILENAME, count);
					
					// Increment the image count
					SharedPreferences.Editor editor = prefs.edit();
					editor.putInt(PREF_IMAGECOUNT, count + 1);
					editor.commit();
				}
				
				file = new File(getStorageDirectory(), filename);
			} while (autogenerated && file.exists());
		}
		
		// Create parent directory as needed
		new File(file.getParent()).mkdirs();
		
		try {
			// Delete old instance of image
			context.getContentResolver().delete(
				MediaStore.Images.Media.EXTERNAL_CONTENT_URI, 
				MediaStore.Images.Media.DATA + "=?", new String[] {file.getAbsolutePath()});
			
			// Write the file to disk
			FileOutputStream os = new FileOutputStream(file);
			boolean written = bm.compress(Bitmap.CompressFormat.PNG, 75, os);
			if (!written) {
				Log.w(TAG, "Failed to write output image to " + file.toString());
			}
			os.close();
			
			// Tell the gallery about the image
			if (written) {
				ContentValues values = new ContentValues();
				values.put(MediaStore.Images.Media.DATA, file.getAbsolutePath());
				values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
				values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
				context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
			}
		}
		catch (IOException e) {}
		
		return file;
	}
	
	public static IImageFilter createEffectFilter(Context context) {
		SharedPreferences prefs = context.getSharedPreferences(PREFS_NAMESPACE, Context.MODE_PRIVATE);
		String filtertype = prefs.getString(PREF_FILTER, PREF_FILTER_DEFAULT);
		
		if (PREF_FILTER_GAMEBOY_SCREEN.equals(filtertype)) {
			return new BayerFilter(Palettes.GAMEBOY_SCREEN);
		}

		if (PREF_FILTER_COMMODORE_64.equals(filtertype)) {
			return new PaletteFilter(Palettes.COMMODORE_64);
		}

		if (PREF_FILTER_ATKINSON.equals(filtertype)) {
			return new AtkinsonFilter();
		}

		if (PREF_FILTER_HALFTONE.equals(filtertype)) {
			return new HalftoneFilter();
		}

		return new BayerFilter(Palettes.GAMEBOY_CAMERA);
	}
	
	public static Bitmaps.Transform createTransformMatrix(
			Context context, int inputwidth, int inputheight, int facing, int orientation, int rotation, 
			int outputwidth, int outputheight, int flags) {
		// Check the current window rotation
		int degrees = 0;
		switch (rotation) {
			case Surface.ROTATION_0: degrees = 0; break;
			case Surface.ROTATION_90: degrees = 90; break;
			case Surface.ROTATION_180: degrees = 180; break;
			case Surface.ROTATION_270: degrees = 270; break;
		}

		int rotate;
		boolean mirror;
		if (facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
			rotate = (orientation + degrees) % 360;
			mirror = true;
		} 
		else {
			rotate = (orientation - degrees + 360) % 360;
			mirror = false;
		}
		
		return Bitmaps.createTransform(inputwidth, inputheight, outputwidth, outputheight, flags, rotate, mirror);
	}

	public static Bitmaps.Transform createTransformMatrix(
			Context context, int inputwidth, int inputheight, int facing, int orientation, int rotation,
			Resolution resolution) {
		int maxwidth, maxheight;
		if (inputwidth >= inputheight) {
			maxwidth = resolution.width;
			maxheight = resolution.height;
		}
		else {
			maxwidth = resolution.height;
			maxheight = resolution.width;
		}
		
		return createTransformMatrix(context, inputwidth, inputheight, facing, orientation, rotation, maxwidth, maxheight, 0);
	}

	public static void toggleImageFilter(Context context) {
		SharedPreferences prefs = context.getSharedPreferences(PREFS_NAMESPACE, Context.MODE_PRIVATE);

		// Switch the active image filter
		String filtertype = prefs.getString(PREF_FILTER, PREF_FILTER_DEFAULT);
		String result;
		
		if (PREF_FILTER_ATKINSON.equals(filtertype)) {
			result = PREF_FILTER_HALFTONE;
		}
		else if (PREF_FILTER_HALFTONE.equals(filtertype)) {
			result = PREF_FILTER_GAMEBOY_CAMERA;
		}
		else {
			result = PREF_FILTER_ATKINSON;
		}
		
		// Notify the user about the filter name
		Toast.makeText(context, getFilterLabel(context, result), Toast.LENGTH_SHORT).show();

		// Commit the change to the preferences
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString(PREF_FILTER, result);
		editor.commit();
	}
	
	private static String getFilterLabel(Context context, String filter) {
		String[] values = context.getResources().getStringArray(R.array.pref_filter_values),
				 labels = context.getResources().getStringArray(R.array.pref_filter_labels);
		for (int i = 0; i < values.length && i < labels.length; i++) {
			if (filter.equals(values[i])) {
				return labels[i];
			}
		}
		
		return "";
	}
}

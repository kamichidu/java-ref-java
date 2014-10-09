package jp.michikusa.chitose.refjava.util;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.concurrent.Future;

import org.junit.Test;

public class InputsTest {
//	@Test
	public void readAll() throws Exception{
		final ProcessBuilder builder = new ProcessBuilder(
			new File(System.getenv("JAVA_HOME"), "bin/jar").getAbsolutePath(),
			"-tf", new File(System.getenv("JAVA_HOME"), "src.zip").getAbsolutePath()
		);

		final Process process = builder.start();

		final Future<CharSequence> out = Inputs.readAll(process.getInputStream());
		final Future<CharSequence> err = Inputs.readAll(process.getErrorStream());

		assertEquals("", out.get().toString());
		assertEquals("", err.get().toString());
	}
}

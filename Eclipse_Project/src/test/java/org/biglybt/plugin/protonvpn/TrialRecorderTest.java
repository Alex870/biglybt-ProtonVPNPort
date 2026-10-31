package org.biglybt.plugin.protonvpn;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrialRecorderTest {

	@TempDir
	Path tmp;

	final PortResult log = PortResult.found(51234, "", 0, 0, "f", 1_700_000_000_000L);
	final PortResult nat = PortResult.found(51234, 51234, "", 0, 7);
	final ComparisonTracker.Evaluation eval = new ComparisonTracker.Evaluation(ComparisonTracker.Outcome.MATCH, 51234, 51234, false, true, 0);

	@Test
	void writesHeaderOnceThenRows() throws Exception {
		File f = tmp.resolve("sub").resolve("trial.csv").toFile();
		TrialRecorder r = new TrialRecorder(f);
		r.append(0, log, nat, eval);
		r.append(1000, log, PortResult.error("timeout", 0, null), new ComparisonTracker.Evaluation(ComparisonTracker.Outcome.LOG_ONLY, 51234, null, false, true, 0));
		List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
		assertEquals(3, lines.size());
		assertEquals(TrialRecorder.HEADER, lines.get(0));
		assertEquals("1970-01-01T00:00:00Z,FOUND,51234,FOUND,51234,51234,MATCH,false,7,2023-11-14T22:13:20Z", lines.get(1));
		assertEquals("1970-01-01T00:00:01Z,FOUND,51234,ERROR,,,LOG_ONLY,false,,2023-11-14T22:13:20Z", lines.get(2));
	}

	@Test
	void rollsOverWhenFull() throws Exception {
		File f = tmp.resolve("trial.csv").toFile();
		try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) {
			raf.setLength(TrialRecorder.MAX_BYTES);
		}
		new TrialRecorder(f).append(0, log, nat, eval);
		assertTrue(new File(f.getPath() + ".1").exists());
		assertEquals(2, Files.readAllLines(f.toPath()).size());
	}
}

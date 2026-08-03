/*******************************************************************************
 * Copyright (c) 2009, 2025 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.cli.internal.commands;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.jacoco.cli.internal.CommandTestBase;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.internal.InputStreams;
import org.jacoco.core.internal.data.CRC64;
import org.jacoco.core.tools.ExecFileLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link Compact}.
 */
public class CompactTest extends CommandTestBase {

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	@Test
	public void should_print_usage_when_no_options_are_given()
			throws Exception {
		execute("compact");

		assertFailure();
		assertContains("\"--classfiles\"", err);
		assertContains("--destfile", err);
		assertContains("java -jar jacococli.jar compact [<execfiles> ...]",
				err);
	}

	@Test
	public void should_keep_only_matching_classes() throws Exception {
		// 用测试类路径上的真实 class 文件计算目标 classId
		final File targetClass = new File(getClassPath(),
				"org/jacoco/cli/internal/CommandTestBase.class");
		final long targetId = CRC64.classId(readAllBytes(targetClass));
		// 输入 exec：一个匹配目标版本，一个不匹配（历史版本残留）
		final File input = new File(tmp.getRoot(), "input.exec");
		final FileOutputStream execout = new FileOutputStream(input);
		final ExecutionDataWriter writer = new ExecutionDataWriter(execout);
		writer.visitClassExecution(new ExecutionData(targetId,
				"org/jacoco/cli/internal/Command", new boolean[] { true }));
		writer.visitClassExecution(new ExecutionData(0x12345678L,
				"org/jacoco/cli/internal/Stale", new boolean[] { true }));
		execout.close();

		final File dest = new File(tmp.getRoot(), "output.exec");
		execute("compact", "--classfiles", getClassPath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertContains("[INFO] Compacted: kept 1 of 2 classes (dropped 1).",
				out);
		assertEquals(Collections.singleton("org/jacoco/cli/internal/Command"),
				loadExecFile(dest));
	}

	@Test
	public void should_keep_session_info() throws Exception {
		final File input = new File(tmp.getRoot(), "session.exec");
		final FileOutputStream execout = new FileOutputStream(input);
		final ExecutionDataWriter writer = new ExecutionDataWriter(execout);
		writer.visitSessionInfo(new SessionInfo("s1", 1L, 2L));
		execout.close();

		final File dest = new File(tmp.getRoot(), "output.exec");
		execute("compact", "--classfiles", getClassPath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		final ExecFileLoader loader = new ExecFileLoader();
		loader.load(dest);
		assertEquals(1, loader.getSessionInfoStore().getInfos().size());
		assertEquals("s1",
				loader.getSessionInfoStore().getInfos().get(0).getId());
	}

	@Test
	public void should_print_warning_when_no_exec_files_are_provided()
			throws Exception {
		final File dest = new File(tmp.getRoot(), "output.exec");
		execute("compact", "--classfiles", getClassPath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertContains("[WARN] No execution data files provided.", out);
		assertEquals(Collections.emptySet(), loadExecFile(dest));
	}

	@Test
	public void should_warn_when_no_classes_match() throws Exception {
		final File input = createExecFile("Stale", 0x12345678L);
		final File classDir = new File(tmp.getRoot(), "emptyClasses");
		classDir.mkdirs();
		final File dest = new File(tmp.getRoot(), "output.exec");

		execute("compact", "--classfiles", classDir.getAbsolutePath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertContains(
				"[WARN] No execution data matches the given class files.",
				out);
		assertEquals(Collections.emptySet(), loadExecFile(dest));
	}

	@Test
	public void should_reject_destfile_equal_to_input() throws Exception {
		final File input = createExecFile("Stale", 0x12345678L);

		execute("compact", "--classfiles", getClassPath(),
				input.getAbsolutePath(), "--destfile",
				input.getAbsolutePath());

		assertFailure();
		assertContains(
				"[ERROR] The destfile must not be one of the input exec files.",
				err);
	}

	@Test
	public void should_scan_nested_directories() throws Exception {
		// 目标 class 放在嵌套目录 classes/a/b/c 下
		final File targetClass = new File(getClassPath(),
				"org/jacoco/cli/internal/CommandTestBase.class");
		final File nestedDir = new File(tmp.getRoot(), "classes/a/b/c");
		nestedDir.mkdirs();
		copy(targetClass, new File(nestedDir, "Command.class"));
		final long targetId = CRC64.classId(readAllBytes(targetClass));
		final File input = createExecFile("Command", targetId);
		final File dest = new File(tmp.getRoot(), "output.exec");

		execute("compact", "--classfiles",
				new File(tmp.getRoot(), "classes").getAbsolutePath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertEquals(Collections.singleton("Command"), loadExecFile(dest));
	}

	@Test
	public void should_scan_jar_class_files() throws Exception {
		final File targetClass = new File(getClassPath(),
				"org/jacoco/cli/internal/CommandTestBase.class");
		final byte[] classBytes = readAllBytes(targetClass);
		final File jar = new File(tmp.getRoot(), "classes.jar");
		final JarOutputStream jarOut = new JarOutputStream(
				new FileOutputStream(jar));
		jarOut.putNextEntry(
				new ZipEntry("org/jacoco/cli/internal/Command.class"));
		jarOut.write(classBytes);
		jarOut.closeEntry();
		jarOut.close();
		final long targetId = CRC64.classId(classBytes);
		final File input = createExecFile("Command", targetId);
		final File dest = new File(tmp.getRoot(), "output.exec");

		execute("compact", "--classfiles", jar.getAbsolutePath(),
				input.getAbsolutePath(), "--destfile",
				dest.getAbsolutePath());

		assertOk();
		assertEquals(Collections.singleton("Command"), loadExecFile(dest));
	}

	private File createExecFile(String name, long id) throws IOException {
		final File file = new File(tmp.getRoot(), name + ".exec");
		final FileOutputStream execout = new FileOutputStream(file);
		final ExecutionDataWriter writer = new ExecutionDataWriter(execout);
		writer.visitClassExecution(
				new ExecutionData(id, name, new boolean[] { true }));
		execout.close();
		return file;
	}

	private Set<String> loadExecFile(File file) throws IOException {
		final ExecFileLoader loader = new ExecFileLoader();
		loader.load(file);
		final Set<String> names = new HashSet<String>();
		for (ExecutionData d : loader.getExecutionDataStore().getContents()) {
			names.add(d.getName());
		}
		return names;
	}

	private byte[] readAllBytes(File file) throws IOException {
		final FileInputStream in = new FileInputStream(file);
		try {
			return InputStreams.readFully(in);
		} finally {
			in.close();
		}
	}

	private void copy(File source, File target) throws IOException {
		final FileOutputStream out = new FileOutputStream(target);
		out.write(readAllBytes(source));
		out.close();
	}

}

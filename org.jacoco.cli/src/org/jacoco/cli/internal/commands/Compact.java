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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.jacoco.cli.internal.Command;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataWriter;
import org.jacoco.core.data.IExecutionDataVisitor;
import org.jacoco.core.internal.ContentTypeDetector;
import org.jacoco.core.internal.InputStreams;
import org.jacoco.core.internal.data.CRC64;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

/**
 * The <code>compact</code> command.
 */
public class Compact extends Command {

	@Argument(usage = "list of JaCoCo *.exec files to read", metaVar = "<execfiles>")
	List<File> execfiles = new ArrayList<File>();

	// 目标版本的 class 文件，用于计算需要保留的 classId 集合
	@Option(name = "--classfiles", usage = "location of the target version Java class files", metaVar = "<path>", required = true)
	List<File> classfiles = new ArrayList<File>();

	@Option(name = "--destfile", usage = "file to write compacted execution data to", metaVar = "<path>", required = true)
	File destfile;

	@Override
	public String description() {
		return "Compacts exec files by removing execution data for classes not matching the given class files.";
	}

	@Override
	public int execute(final PrintWriter out, final PrintWriter err)
			throws IOException {
		final File parent = destfile.getParentFile();
		if (parent != null) {
			parent.mkdirs();
		}
		// 第一阶段：遍历目标版本 class 文件，构建需要保留的 classId 集合
		final Set<Long> targetIds = collectClassIds(out);
		int total = 0;
		int kept = 0;
		final BufferedOutputStream output = new BufferedOutputStream(
				new FileOutputStream(destfile));
		try {
			final ExecutionDataWriter writer = new ExecutionDataWriter(output);
			if (execfiles.isEmpty()) {
				out.println("[WARN] No execution data files provided.");
			} else {
				for (final File file : execfiles) {
					out.printf("[INFO] Loading execution data file %s.%n",
							file.getAbsolutePath());
					final BufferedInputStream input = new BufferedInputStream(
							new FileInputStream(file));
					try {
						final ExecutionDataReader reader = new ExecutionDataReader(
								input);
						reader.setSessionInfoVisitor(writer);
						final Counter counter = new Counter(writer,
								targetIds);
						reader.setExecutionDataVisitor(counter);
						reader.read();
						total += counter.total;
						kept += counter.kept;
					} finally {
						input.close();
					}
				}
			}
		} finally {
			output.close();
		}
		out.printf("[INFO] Compacted: kept %d of %d classes (dropped %d).%n",
				Integer.valueOf(kept), Integer.valueOf(total),
				Integer.valueOf(total - kept));
		if (total > 0 && kept == 0) {
			out.println(
					"[WARN] No execution data matches the given class files. The class files may be from an old version.");
		}
		return 0;
	}

	/**
	 * 遍历目标版本 class 文件（目录递归、zip 内条目递归），计算全部 classId
	 *
	 * @param out
	 *            输出流，打印扫描到的类数量
	 * @return 目标版本 classId 集合
	 * @throws IOException
	 *             文件读取失败时抛出
	 */
	private Set<Long> collectClassIds(final PrintWriter out)
			throws IOException {
		final Set<Long> ids = new HashSet<Long>();
		int count = 0;
		for (final File file : classfiles) {
			count += collectClassIds(file, ids);
		}
		out.printf("[INFO] Found %d classes in target class files.%n",
				Integer.valueOf(count));
		return ids;
	}

	private int collectClassIds(final File file, final Set<Long> ids)
			throws IOException {
		if (file.isDirectory()) {
			int count = 0;
			final File[] children = file.listFiles();
			if (children != null) {
				for (final File child : children) {
					count += collectClassIds(child, ids);
				}
			}
			return count;
		}
		final FileInputStream in = new FileInputStream(file);
		try {
			return collectStreamClassIds(in, ids);
		} finally {
			in.close();
		}
	}

	/**
	 * 识别流内容类型：class 文件直接计算 classId，zip 则遍历内部条目，
	 * 其余类型（gzip/pack200/未知）跳过
	 */
	private int collectStreamClassIds(final InputStream input,
			final Set<Long> ids) throws IOException {
		final ContentTypeDetector detector = new ContentTypeDetector(input);
		switch (detector.getType()) {
		case ContentTypeDetector.CLASSFILE:
			ids.add(Long.valueOf(CRC64.classId(InputStreams
					.readFully(detector.getInputStream()))));
			return 1;
		case ContentTypeDetector.ZIPFILE:
			return collectZipClassIds(detector.getInputStream(), ids);
		default:
			return 0;
		}
	}

	/**
	 * 遍历 zip 内后缀为 .class 的条目，嵌套 zip 递归处理
	 */
	private int collectZipClassIds(final InputStream input,
			final Set<Long> ids) throws IOException {
		final ZipInputStream zip = new ZipInputStream(input);
		ZipEntry entry;
		int count = 0;
		while ((entry = zip.getNextEntry()) != null) {
			if (entry.getName().endsWith(".class")) {
				count += collectStreamClassIds(zip, ids);
			}
		}
		return count;
	}

	/**
	 * 统计执行数据总条数与保留条数，并将目标版本的执行数据转发给 writer
	 */
	private static class Counter implements IExecutionDataVisitor {

		private final ExecutionDataWriter writer;

		private final Set<Long> targetIds;

		private int total = 0;

		private int kept = 0;

		Counter(final ExecutionDataWriter writer, final Set<Long> targetIds) {
			this.writer = writer;
			this.targetIds = targetIds;
		}

		public void visitClassExecution(final ExecutionData data) {
			total++;
			// 只保留 classId 命中目标版本的条目，历史版本的条目直接丢弃
			if (targetIds.contains(Long.valueOf(data.getId()))) {
				kept++;
				writer.visitClassExecution(data);
			}
		}
	}

}

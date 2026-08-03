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

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import org.jacoco.cli.internal.Command;
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
		return 0;
	}

}

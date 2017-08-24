/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2012 - 2016 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package net.imagej.maven;

import java.io.File;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.resolver.filter.ArtifactFilter;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilderException;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import org.apache.maven.shared.dependency.graph.traversal.CollectingDependencyNodeVisitor;

/**
 * Copies .jar artifacts and their dependencies into an ImageJ.app/ directory
 * structure.
 * 
 * @author Johannes Schindelin
 */
@Mojo(name = "copy-jars", requiresProject = true, requiresOnline = true)
public class CopyJarsMojo extends AbstractCopyJarsMojo {

	/**
	 * The name of the property pointing to the ImageJ.app/ directory.
	 * <p>
	 * If no property of that name exists, or if it is not a directory, no .jar
	 * files are copied.
	 * </p>
	 */
	@Parameter(property = imagejDirectoryProperty, required = false)
	private String imagejDirectory;

	/**
	 * Whether to delete other versions when copying the files.
	 * <p>
	 * When copying a file and its dependencies to an ImageJ.app/ directory and
	 * there are other versions of the same file, we can warn or delete those
	 * other versions.
	 * </p>
	 */
	@Parameter(property = deleteOtherVersionsProperty, defaultValue = "false")
	private boolean deleteOtherVersions;

	/**
	 * Project
	 */
	@Parameter(defaultValue = "${project}", required=true, readonly = true)
	private MavenProject project;

	/**
	 * Session
	 */
	@Parameter(defaultValue = "${session}")
	private MavenSession session;

	@Component
	private DependencyGraphBuilder treeBuilder;

	private File imagejDir;

	@Override
	public void execute() throws MojoExecutionException {
		if (imagejDirectory == null) {
			if (hasIJ1Dependency(project)) getLog().info(
				"Property '" + imagejDirectoryProperty + "' unset; Skipping copy-jars");
			return;
		}
		final String interpolated = interpolate(imagejDirectory, project, session);
		imagejDir = new File(interpolated);
		if (!imagejDir.isDirectory()) {
			getLog().warn(
				"'" + imagejDirectory + "'" +
					(interpolated.equals(imagejDirectory) ? "" : " (" + imagejDirectory + ")") +
					" is not an ImageJ.app/ directory; Skipping copy-jars");
			return;
		}

		try {
			ArtifactFilter artifactFilter =
				new ScopeArtifactFilter(Artifact.SCOPE_COMPILE);

			ProjectBuildingRequest request = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
			request.setProject( project );
			DependencyNode rootNode =
				treeBuilder.buildDependencyGraph(request, artifactFilter);

			CollectingDependencyNodeVisitor visitor =
				new CollectingDependencyNodeVisitor();
			rootNode.accept(visitor);

			for (final DependencyNode dependencyNode : visitor.getNodes())
			{
				final Artifact artifact = dependencyNode.getArtifact();
				final String scope = artifact.getScope();
				if (scope != null && !scope.equals(Artifact.SCOPE_COMPILE) &&
					!scope.equals(Artifact.SCOPE_RUNTIME)) continue;
				try {
					installArtifact(artifact, imagejDir, false,
						deleteOtherVersions);
				}
				catch (Exception e) {
					throw new MojoExecutionException("Could not copy " + artifact +
						" to " + imagejDirectory, e);
				}
			}
		}
		catch (DependencyGraphBuilderException e) {
			throw new MojoExecutionException("Could not get the dependencies for " +
				project.getArtifactId(), e);
		}
	}
}

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
import java.io.IOException;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.dependencies.resolve.DependencyResolver;
import org.apache.maven.shared.dependencies.resolve.DependencyResolverException;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;

/**
 * Downloads .jar artifacts and their dependencies into an ImageJ.app/ directory
 * structure.
 * 
 * @author Johannes Schindelin
 */
@Mojo(name = "install-artifact", requiresProject = true, requiresOnline = true)
public class InstallArtifactMojo extends AbstractCopyJarsMojo {

	/**
	 * The name of the property pointing to the ImageJ.app/ directory.
	 * <p>
	 * If no property of that name exists, or if it is not a directory, no .jar
	 * files are copied.
	 * </p>
	 */
	@Parameter(property = imagejDirectoryProperty)
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

	/**
	 * The groupId of the artifact to download. Ignored if {@link #artifact} is
	 * used.
	 */
	@Parameter(property = "groupId")
	private String groupId;

	/**
	 * The artifactId of the artifact to download. Ignored if {@link #artifact} is
	 * used.
	 */
	@Parameter(property="artifactId")
	private String artifactId;

	/**
	 * The version of the artifact to download. Ignored if {@link #artifact} is
	 * used.
	 */
	@Parameter(property="version")
	private String version;

	/**
	 * The packaging of the artifact to download. Ignored if {@link #artifact} is
	 * used.
	 */
	@Parameter(property = "packaging", defaultValue = "jar")
	private String packaging = "jar";

	/**
	 * A string of the form groupId:artifactId:version[:packaging][:classifier].
	 */
	@Parameter(property = "artifact")
	private String artifact;

	/**
	 * The dependency tree builder to use.
	 */
	@Component(hint = "default")
	private DependencyGraphBuilder dependencyGraphBuilder;

	/**
	 * The dependency resolver to.
	 */
	@Component
	private DependencyResolver dependencyResolver;

	/**
	 * Whether to force overwriting files.
	 */
	@Parameter(property = "force")
	private boolean force;

	private DefaultDependableCoordinate coordinate = new DefaultDependableCoordinate();

	public void execute() throws MojoExecutionException, MojoFailureException {
		if (imagejDirectory == null) {
			throw new MojoExecutionException(
				"The '"+imagejDirectoryProperty+"' property is unset!");
		}
		File imagejDir = new File(imagejDirectory);
		if (!imagejDir.isDirectory() && !imagejDir.mkdirs()) {
			throw new MojoFailureException("Could not make directory: " +
				imagejDir);
		}

		/*
		 * Determine GAV to download
		 */
		if (artifactId == null && artifact == null) {
			throw new MojoFailureException(
				"No artifact specified (e.g. by -Dartifact=net.imagej:ij:1.48p)");
		}
		if (artifact != null) {
			String[] tokens = artifact.split(":");
			if (tokens.length != 3) {
				throw new MojoFailureException(
					"Invalid artifact, you must specify groupId:artifactId:version " +
						artifact);
			}
			groupId = tokens[0];
			artifactId = tokens[1];
			version = tokens[2];

			coordinate.setGroupId(groupId);
			coordinate.setArtifactId(artifactId);
			coordinate.setVersion(version);

			if (tokens.length >= 4) {
				coordinate.setType(tokens[3]);
			}
			if (tokens.length == 5) {
				coordinate.setClassifier(tokens[4]);
			}
		}

		/*
		 * Install artifact
		 */
		try {
			ProjectBuildingRequest buildingRequest =
				new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
			buildingRequest.setProject(project);

			Iterable<ArtifactResult> resolveDependencies = dependencyResolver
				.resolveDependencies(buildingRequest, coordinate, null);
			for (ArtifactResult result : resolveDependencies) {
				try {
					installArtifact(result.getArtifact(), imagejDir, false, deleteOtherVersions);
				}
				catch (IOException e) {
					throw new MojoExecutionException("Couldn't download artifact " +
						artifact + ": " + e.getMessage(), e);
				}
			}
		}
		catch (DependencyResolverException e) {
			throw new MojoExecutionException(
				"Couldn't resolve dependencies for artifact: " + e.getMessage(), e);
		}
	}
}

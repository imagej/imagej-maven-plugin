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
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.artifact.filter.resolve.ScopeFilter;
import org.apache.maven.shared.artifact.filter.resolve.TransformableFilter;
import org.apache.maven.shared.artifact.resolve.ArtifactResult;
import org.apache.maven.shared.dependencies.DefaultDependableCoordinate;
import org.apache.maven.shared.dependencies.resolve.DependencyResolver;
import org.apache.maven.shared.dependencies.resolve.DependencyResolverException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;

/**
 * Copies .jar artifacts and their dependencies into an ImageJ.app/ directory
 * structure.
 * 
 * @author Johannes Schindelin
 */
@Mojo(name = "copy-jars", requiresProject = true, requiresOnline = true)
public class CopyJarsMojo extends AbstractCopyJarsMojo {

	/**
	 * Path to the ImageJ.app/ directory to which artifacts are copied.
	 * <p>
	 * If it is not a directory, no .jar files are copied.
	 * </p>
	 */
	@Parameter(property = imagejDirectoryProperty, required = false)
	private String imagejDirectory;

	/**
	 * The name of the property pointing to the subdirectory (beneath e.g.
	 * {@code jars/} or {@code plugins/}) to which the artifact should be copied.
	 * <p>
	 * If no property of that name exists, no subdirectory will be used.
	 * </p>
	 */
	@Parameter(property = imagejSubdirectoryProperty, required = false)
	private String imagejSubdirectory;

	/**
	 * Whether to delete other versions when copying the files.
	 * <p>
	 * When copying a file and its dependencies to an ImageJ.app/ directory and
	 * there are other versions of the same file, we can warn or delete those
	 * other versions.
	 * </p>
	 */
	@Deprecated
	@Parameter(property = deleteOtherVersionsProperty)
	private boolean deleteOtherVersions;

	/**
	 * Whether to delete other versions when copying the files.
	 * <p>
	 * When copying a file and its dependencies to an ImageJ.app/ directory and
	 * there are other versions of the same file, we can warn or delete those
	 * other versions.
	 * </p>
	 */
	@Parameter(property = deleteOtherVersionsPolicyProperty, defaultValue = "older")
	private OtherVersions deleteOtherVersionsPolicy;

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
	 * The dependency resolver to.
	 */
	@Component
	private DependencyResolver dependencyResolver;

	private DefaultDependableCoordinate coordinate = new DefaultDependableCoordinate();

	private File imagejDir;

	@Parameter( defaultValue = "${mojoExecution}", readonly = true )
	MojoExecution mojoExecution;

	@Parameter(property = ignoreDependenciesProperty, defaultValue = "false")
	private boolean ignoreDependencies;

	@Override
	public void execute() throws MojoExecutionException {
		// Keep backwards compatibility to delete.other.versions
		ExpressionEvaluator evaluator = new PluginParameterExpressionEvaluator(session, mojoExecution);
		try {
			Object evaluate = evaluator.evaluate("${"+deleteOtherVersionsProperty+"}");
			if (evaluate != null) {
				getLog().warn("Property '" + deleteOtherVersionsProperty + "' is deprecated. Use '"+ deleteOtherVersionsPolicyProperty +"' instead");
				deleteOtherVersionsPolicy = deleteOtherVersions ? OtherVersions.older : OtherVersions.never;
			}
		}
		catch (ExpressionEvaluationException e) {
			getLog().warn(e);
		}

		if (imagejDirectory == null) {
			if (hasIJ1Dependency(project)) getLog().info(
				"Property '" + imagejDirectoryProperty + "' unset; Skipping copy-jars");
			return;
		}
		final String interpolated = interpolate(imagejDirectory, project, session);
		imagejDir = new File(interpolated);

		if (imagejSubdirectory == null) {
			getLog().info("No property name for the " + imagejSubdirectoryProperty +
				" directory location was specified; Installing in default location");
		}

		if (!imagejDir.isDirectory()) {
			getLog().warn(
				"'" + imagejDirectory + "'" +
					(interpolated.equals(imagejDirectory) ? "" : " (" + imagejDirectory + ")") +
					" is not an ImageJ.app/ directory; Skipping copy-jars");
			return;
		}

		// Initialize coordinate for resolving
		coordinate.setGroupId(project.getGroupId());
		coordinate.setArtifactId(project.getArtifactId());
		coordinate.setVersion(project.getVersion());

		try {
			TransformableFilter scopeFilter = ScopeFilter.excluding("system", "provided", "test");

			ProjectBuildingRequest buildingRequest = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
			buildingRequest.setProject( project );

			Iterable<ArtifactResult> resolveDependencies = dependencyResolver
					.resolveDependencies(buildingRequest, coordinate, scopeFilter);
				for (ArtifactResult result : resolveDependencies) {
					try {
						if (project.getArtifact().equals(result.getArtifact())) {
							installArtifact(result.getArtifact(), imagejDir, imagejSubdirectory, false,
								deleteOtherVersionsPolicy);
							continue;
						}
						if ( !ignoreDependencies )
							installArtifact( result.getArtifact(), imagejDir, false, deleteOtherVersionsPolicy );
					}
					catch (IOException e) {
						throw new MojoExecutionException("Couldn't download artifact " +
							result.getArtifact() + ": " + e.getMessage(), e);
					}
				}
		}
		catch (DependencyResolverException e) {
			throw new MojoExecutionException(
				"Couldn't resolve dependencies for artifact: " + e.getMessage(), e);
		}
	}
}

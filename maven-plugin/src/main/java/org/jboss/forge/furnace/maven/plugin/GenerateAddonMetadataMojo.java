/**
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Eclipse Public License version 1.0, available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package org.jboss.forge.furnace.maven.plugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.jboss.forge.furnace.util.Streams;

/**
 * Generates an addon metadata class
 * 
 * @author <a href="mailto:ggastald@redhat.com">George Gastaldi</a>
 */
@Mojo(defaultPhase = LifecyclePhase.GENERATE_SOURCES, name = "generate-metadata", threadSafe = true)
public class GenerateAddonMetadataMojo extends AbstractMojo
{
   /**
    * Tokens used in the template
    */
   private static final String TOKEN_ADDON_PACKAGE = "${addonPackage}";
   private static final String TOKEN_ADDON_NAME = "${addonName}";
   private static final String TOKEN_ADDON_VERSION = "${addonVersion}";

   /**
    * Specify output directory where the Java files are generated.
    */
   @Parameter(defaultValue = "${project.build.directory}/generated-sources/furnace")
   private File outputDir;

   /**
    * The package where this AddonMetadata class will be created
    */
   @Parameter(defaultValue = "${project.groupId}.${project.artifactId}")
   private String targetPackage;

   /**
    * Skip this execution ?
    */
   @Parameter(property = "furnace.metadata.skip")
   private boolean skip;

   /**
    * Overwrite addon metadata. Default is true
    */
   @Parameter(property = "furnace.metadata.overwrite", defaultValue = "true")
   private boolean overwrite = true;

   /**
    * The current maven project
    */
   @Parameter(defaultValue = "${project}", required = true, readonly = true)
   private MavenProject mavenProject;

   @Override
   public void execute() throws MojoExecutionException, MojoFailureException
   {
      Log log = getLog();
      if (skip)
      {
         log.info("Execution skipped.");
         return;
      }
      String template = Streams.toString(getClass().getResourceAsStream("AddonMetadata.jv"));
      log.info("Generating metadata for this addon ...");

      if (!outputDir.exists())
      {
         outputDir.mkdirs();
      }
      else if (overwrite)
      {
         try
         {
            deleteDirectory(outputDir);
            outputDir.mkdirs();
         }
         catch (IOException e)
         {
            throw new MojoExecutionException("Could not delete " + outputDir, e);
         }
      }

      String groupId = mavenProject.getGroupId();
      if (groupId == null)
      {
         groupId = mavenProject.getParent().getGroupId();
      }
      String artifactId = mavenProject.getArtifactId();
      String version = mavenProject.getVersion();
      if (version == null)
      {
         version = mavenProject.getParent().getVersion();
      }
      String addonName = groupId + ":" + artifactId;
      template = template.replace(TOKEN_ADDON_PACKAGE, targetPackage);
      template = template.replace(TOKEN_ADDON_NAME, addonName);
      template = template.replace(TOKEN_ADDON_VERSION, version);

      File targetFile = new File(new File(outputDir, targetPackage.replace('.', File.separatorChar)),
               "AddonMetadata.java");
      if (!targetFile.getParentFile().exists())
         targetFile.getParentFile().mkdirs();
      try
      {
         Files.write(targetFile.toPath(), template.getBytes(), StandardOpenOption.CREATE);
      }
      catch (IOException e)
      {
         throw new MojoExecutionException("Error while writing file " + targetFile.getAbsolutePath(), e);
      }
      // Tell Maven that there are some new source files underneath the output directory.
      mavenProject.addCompileSourceRoot(outputDir.getPath());
   }

   private void deleteDirectory(File addonRepository) throws IOException
   {
      Files.walkFileTree(addonRepository.toPath(), new SimpleFileVisitor<Path>()
      {
         @Override
         public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException
         {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
         }

         @Override
         public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException
         {
            Files.delete(dir);
            return FileVisitResult.CONTINUE;
         }
      });
   }

}

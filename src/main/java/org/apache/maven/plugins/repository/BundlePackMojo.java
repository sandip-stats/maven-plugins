package org.apache.maven.plugins.repository;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.License;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.settings.Settings;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.components.interactivity.InputHandler;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.WriterFactory;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Packs artifacts already available in a local repository in a bundle for an
 * upload requests. It requires that the artifact has a POM in the local
 * repository. It will check for mandatory elements, asking interactively for
 * missing values. Can be used to generate bundles for third parties artifacts
 * that have been manually added to the local repository.
 *
 * @goal bundle-pack
 * @requiresProject false
 * @since 2.1
 */
public class BundlePackMojo
    extends AbstractMojo
{
    public static final String POM = "pom.xml";

    /**
     * Jar archiver.
     * 
     * @component role="org.codehaus.plexus.archiver.Archiver" roleHint="jar"
     */
    protected JarArchiver jarArchiver;

    /**
     * Artifact resolver.
     * 
     * @component
     */
    protected ArtifactResolver artifactResolver;

    /**
     * Artifact factory.
     * 
     * @component
     */
    protected ArtifactFactory artifactFactory;

    /**
     * Local maven repository.
     * 
     * @parameter default-value="${localRepository}"
     * @required
     * @readonly
     */
    protected ArtifactRepository localRepository;

    /**
     * @component
     */
    protected InputHandler inputHandler;

    /**
     * Directory where the upload-bundle will be created.
     *
     * @parameter default-value="${basedir}"
     * @readonly
     */
    protected String basedir;

    /**
     * GroupId for the artifact to create an upload bundle for.
     *
     * @parameter expression="${groupId}"
     */
    protected String groupId;

    /**
     * ArtifactId for the artifact to create an upload bundle for.
     *
     * @parameter expression="${artifactId}"
     */
    protected String artifactId;

    /**
     * Version for the artifact to create an upload bundle for.
     * 
     * @parameter expression="${version}"
     */
    protected String version;
    
    /**
     * @parameter default-value="${settings}"
     * @readonly
     */
    protected Settings settings;

    public void execute()
        throws MojoExecutionException
    {
        readArtifactDataFromUser();

        Artifact artifact = artifactFactory.createProjectArtifact( groupId, artifactId, version );

        try
        {
            artifactResolver.resolve( artifact, Collections.EMPTY_LIST, localRepository );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new MojoExecutionException( "Unable to resolve artifact " + artifact.getId(), e );
        }
        catch ( ArtifactNotFoundException e )
        {
            throw new MojoExecutionException( "Artifact " + artifact.getId() + " not found in local repository", e );
        }

        File pom = artifact.getFile();

        File dir = pom.getParentFile();

        Model model = readPom( pom );

        boolean rewrite = false;
        try
        {

            if ( model.getPackaging() == null )
            {
                model.setPackaging( "jar" );
                rewrite = true;
            }
            if ( model.getName() == null )
            {
                getLog().info( "Project name is missing, please type the project name [" + artifactId + "]:" );
                model.setName( inputHandler.readLine() );
                if ( model.getName() == null )
                {
                    model.setName( artifactId );
                }
                rewrite = true;
            }
            if ( model.getDescription() == null )
            {
                getLog().info( "Project description is missing, please type the project description:" );
                model.setDescription( inputHandler.readLine() );
                rewrite = true;
            }
            if ( model.getUrl() == null )
            {
                getLog().info( "Project URL is missing, please type the project URL:" );
                model.setUrl( inputHandler.readLine() );
                rewrite = true;
            }

            List licenses = model.getLicenses();
            if ( licenses.isEmpty() )
            {
                License license = new License();

                getLog().info( "License name is missing, please type the license name:" );
                license.setName( inputHandler.readLine() );
                getLog().info( "License URL is missing, please type the license URL:" );
                license.setUrl( inputHandler.readLine() );
                licenses.add( license );
                rewrite = true;
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }

        try
        {
            if ( rewrite )
            {
                new MavenXpp3Writer().write( WriterFactory.newXmlWriter( pom ), model );
            }

            String finalName = null;

            if ( model.getBuild() != null )
            {
                finalName = model.getBuild().getFinalName();
            }
            if ( finalName == null )
            {
                finalName = model.getArtifactId() + "-" + model.getVersion();
            }
            
            boolean batchMode = settings == null ? false : !settings.isInteractiveMode();
            List files = BundleUtils.selectProjectFiles( dir, inputHandler, finalName, pom, getLog(), batchMode );

            File bundle = new File( basedir, finalName + "-bundle.jar" );

            jarArchiver.addFile( pom, POM );

            boolean artifactChecks = !"pom".equals( model.getPackaging() );
            boolean sourcesFound = false;
            boolean javadocsFound = false;
            
            for ( Iterator it = files.iterator(); it.hasNext(); )
            {
                File f = (File) it.next();
                if ( artifactChecks && f.getName().endsWith( finalName + "-sources.jar" ) )
                {
                    sourcesFound = true;
                }
                else if ( artifactChecks && f.getName().equals( finalName + "-javadoc.jar" ) )
                {
                    javadocsFound = true;
                }
                
                jarArchiver.addFile( f, f.getName() );
            }
            
            if ( artifactChecks && !sourcesFound )
            {
                getLog().warn( "Sources not included in upload bundle." );
            }

            if ( artifactChecks && !javadocsFound )
            {
                getLog().warn( "Javadoc not included in upload bundle." );
            }

            jarArchiver.setDestFile( bundle );

            jarArchiver.createArchive();

        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
        catch ( ArchiverException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }

    }

    /**
     * Read groupId, artifactId and version from the user on the command line,
     * if they were not provided as parameters.
     *
     * @throws MojoExecutionException If the values can't be read
     */
    private void readArtifactDataFromUser()
        throws MojoExecutionException
    {
        try
        {
            if ( groupId == null )
            {
                getLog().info( "groupId? " );

                groupId = inputHandler.readLine();

            }

            if ( artifactId == null )
            {
                getLog().info( "artifactId? " );
                artifactId = inputHandler.readLine();
            }

            if ( version == null )
            {
                getLog().info( "version? " );
                version = inputHandler.readLine();
            }
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }
    }

    /**
     * Read the POM file.
     *
     * @param pom The file to read
     * @return A Maven Model
     * @throws MojoExecutionException if something goes wrong when reading the file
     */
    private Model readPom( File pom )
        throws MojoExecutionException
    {
        Model model;
        try
        {
            model = new MavenXpp3Reader().read( ReaderFactory.newXmlReader( pom ) );
        }
        catch ( XmlPullParserException e )
        {
            throw new MojoExecutionException( "Unable to parse POM at " + pom.getAbsolutePath() + ": " + e.getMessage(),
                                              e );
        }
        catch ( FileNotFoundException e )
        {
            throw new MojoExecutionException( "Unable to read POM at " + pom.getAbsolutePath() + ": " + e.getMessage(),
                                              e );
        }
        catch ( IOException e )
        {
            throw new MojoExecutionException( "Unable to read POM at " + pom.getAbsolutePath() + ": " + e.getMessage(),
                                              e );
        }
        return model;
    }

}

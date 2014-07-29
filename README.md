ImageJ Maven Plugin
===================

imagej-maven-plugin is a Maven plugin to help with developing ImageJ and ImageJ
plugins.

It provides one goal:

* __copy-jars__ (as part of the _install_ phase of the life cycle): copies the
  artifact and all its dependencies into an _ImageJ.app/_ directory structure;
  ImageJ 1.x plugins (identified by containing a _plugins.config_ file) get
  copied to the _plugins/_ subdirectory and all other _.jar_ files to _jars/_.
  It expects the location of the _ImageJ.app/_ directory to be specified in the
  property _imagej.app.directory_ (which can be set on the Maven command-line).
  If said property is not set, the __copy-jars__ goal is skipped.

It is recommended to use it implicitly by making the
[SciJava POM](https://github.com/scijava/pom-scijava) the parent project:

```xml
<project ...>
  <parent>
    <groupId>org.scijava</groupId>
    <artifactId>pom-scijava</artifactId>
    <version>1.162</version>
  </parent>
  ...
</project>
```

Alternatively, you can include the plugin explicitly in the life cycle:

```xml
<project ...>
  <build>
    <plugins>
      <!-- Enable copying the artifacts and dependencies by setting
           the 'imagej.app.directory' property to a valid directory. -->
      <plugin>
        <groupId>net.imagej</groupId>
        <artifactId>imagej-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>copy-jars</id>
            <phase>install</phase>
            <goals>
              <goal>copy-jars</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

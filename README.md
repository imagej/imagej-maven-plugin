**This Maven plugin is deprecated since the _copy-jars_ goal has been migrated to [`scijava-maven-plugin`](https://github.com/scijava/scijava-maven-plugin). We have made sure to keep backward compatibility with `imagej.*` properties, but recommend to replace them with `scijava.*` properties when switching to `scijava-maven-plugin`.**

**If your project has at least `pom-scijava:X.Y.Z` as parent, `scijava-maven-plugin` has been made the default Maven plugin for handling installation of SciJava and ImageJ plugins.**

---

[![](https://img.shields.io/maven-central/v/net.imagej/imagej-maven-plugin.svg)](http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22net.imagej%22%20AND%20a%3A%22imagej-maven-plugin%22)
[![](https://travis-ci.org/imagej/imagej-maven-plugin.svg?branch=master)](https://travis-ci.org/imagej/imagej-maven-plugin)

ImageJ Maven Plugin
===================

imagej-maven-plugin is a Maven plugin to help with developing ImageJ and ImageJ
plugins.

It provides one goal:

* __copy-jars__ (as part of the _install_ phase of the life cycle): copies the
  artifact and all its dependencies into an _ImageJ.app/_ directory structure;
  ImageJ 1.x plugins (identified by containing a _plugins.config_ file) get
  copied to the _plugins/_ subdirectory and all other _.jar_ files to _jars/_.
  However, you can override this decision by setting the property
  _imagej.app.subdirectory_ to a specific subdirectory. It expects the location
  of the _ImageJ.app/_ directory to be specified in the property
  _imagej.app.directory_ (which can be set on the Maven command-line). If said
  property is not set, the __copy-jars__ goal is skipped.

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

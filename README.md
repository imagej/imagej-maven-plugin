ImageJ Maven Plugin
===================

imagej-maven-plugin is a Maven plugin to help with developing ImageJ and ImageJ plugins.

It provides two goals:

* __copy-jars__ (as part of the _install_ phase of the life cycle): copies the artifact and all its dependencies into an _ImageJ.app/_ directory structure; ImageJ 1.x plugins (identified by containing a _plugins.config_ file) get copied to the _plugins/_ subdirectory and all other _.jar_ files to _jars/_. It expects the location of the _ImageJ.app/_ directory to be specified in the property _imagej.app.location_ (which can be set on the Maven command-line). If said property is not set, the __copy-jars__ goal is skipped.
* __set-rootdir__ (as part of the _validate_ phase of the life cycle): finds the project root directory of nested Maven projects and sets the __rootdir__ property to point there. This goal is useful if you want to define the location of the _ImageJ.app/_ directory relative to the project root directory.

It is recommended to use it implicitly by making the [SciJava POM](http://github.com/scijava/scijava-common/blob/master/pom-scijava) the parent project:

```xml
<project ...>
  <parent>
    <groupId>org.scijava</groupId>
    <artifactId>pom-scijava</artifactId>
    <version>1.27</version>
  </parent>
  ...
  <!-- NB: for project parent -->
  <repositories>
    <repository>
      <id>imagej.releases</id>
      <url>http://maven.imagej.net/content/repositories/releases</url>
    </repository>
  </repositories>
</project>
```

Alternatively, you can include the plugin [explicitly](https://github.com/scijava/scijava-common/blob/5c1764b9e6fca45977a7ee98823362039a4d41ad/pom-scijava/pom.xml#L48) in the life cycle.

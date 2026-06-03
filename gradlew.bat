@rem Gradle wrapper batch script for Windows
@echo off
set GRADLE_OPTS=%GRADLE_OPTS%
set APP_HOME=%~dp0
set CLASSPATH=%APP_HOME%gradle\wrapper\gradle-wrapper.jar
java %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*

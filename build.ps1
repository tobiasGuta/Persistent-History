$ErrorActionPreference = 'Stop'

Write-Host 'Persistent HTTP History v2.1.0 build'
Write-Host '--------------------------------'

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java was not found in PATH. Install JDK 21 (or lower supported by Burp) first.'
}
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven was not found in PATH. Install Maven, reopen PowerShell, then run this script again.'
}

java -version
mvn clean package

$jar = Join-Path $PSScriptRoot 'target\burp-persistent-history-2.1.0.jar'
if (-not (Test-Path $jar)) {
    throw "Build finished but expected JAR was not found: $jar"
}

Write-Host ''
Write-Host "Built: $jar"
Write-Host 'Load it in Burp: Extensions -> Add -> Java -> select the JAR.'

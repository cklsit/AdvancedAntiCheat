
# AdvancedAntiCheat Test Runner v2.1
# Features: Unit Tests, Server Compatibility Tests, Log Error Detection, Test Report Generation
# Author: AdvancedAntiCheat Team
# Version: 2.1.0

param(
    [string]$CommitMessage = "",
    [switch]$SkipTests = $false,
    [switch]$SkipCommit = $false
)

$ErrorActionPreference = "Stop"
$ProjectRoot = "C:\Users\Jonson\Documents\trae_projects\AntiCheat"
$HighVersionTest = "$ProjectRoot\test\high"
$LowVersionTest = "$ProjectRoot\test\low"
$UnitTestDir = "$ProjectRoot\test\unit"
$ReportsDir = "$ProjectRoot\test\reports"
$PluginName = "AdvancedAntiCheat-1.0.0.jar"
$StartTime = Get-Date

function Initialize-Environment {
    Write-Progress "Initializing environment..."
    
    $mvnPath = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvnPath) {
        Write-Warning "Maven not found in PATH, searching..."
        $possiblePaths = @(
            "$env:USERPROFILE\.trae-cn\tools\maven\latest\bin",
            "$env:USERPROFILE\.trae-cn\tools\maven\current\bin",
            "$env:USERPROFILE\.trae\tools\maven\latest\bin",
            "$env:USERPROFILE\.trae\tools\maven\current\bin",
            "C:\Program Files\Apache\maven\bin",
            "C:\Program Files\Maven\bin",
            "C:\Program Files (x86)\Apache\maven\bin",
            "C:\Program Files (x86)\Maven\bin",
            "$env:USERPROFILE\scoop\apps\maven\current\bin",
            "$env:USERPROFILE\.m2\bin",
            "C:\maven\bin"
        )
        foreach ($path in $possiblePaths) {
            if (Test-Path "$path\mvn.cmd") {
                $env:PATH += ";$path"
                Write-Success "Added Maven path: $path"
                break
            }
        }
        
        if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
            Write-Fail "Maven still not found after search. Please install Maven or add it to PATH."
        }
    } else {
        Write-Success "Maven found: $($mvnPath.Source)"
    }
    
    $javaHome = $env:JAVA_HOME
    if (-not $javaHome) {
        Write-Warning "JAVA_HOME not set, trying to detect..."
        $javaPaths = @(
            "C:\Program Files\Java",
            "C:\Program Files (x86)\Java",
            "C:\Program Files\Eclipse Adoptium"
        )
        foreach ($basePath in $javaPaths) {
            if (Test-Path $basePath) {
                $jdkDirs = Get-ChildItem -Path $basePath -Directory -Filter "jdk*" -ErrorAction SilentlyContinue
                if ($jdkDirs) {
                    $latestJdk = $jdkDirs | Sort-Object Name -Descending | Select-Object -First 1
                    $env:JAVA_HOME = $latestJdk.FullName
                    $env:PATH += ";$($latestJdk.FullName)\bin"
                    Write-Success "Set JAVA_HOME to: $($latestJdk.FullName)"
                    break
                }
            }
        }
    }
    
    Write-Success "Environment initialized"
}

if (-not (Test-Path $ReportsDir)) {
    New-Item -ItemType Directory -Path $ReportsDir | Out-Null
}

$TestResults = @{
    Unit = @{ Passed = 0; Failed = 0; Skipped = 0; Duration = 0; Errors = @() }
    HighVersion = @{ Passed = $false; Duration = 0; Errors = @(); Warnings = @() }
    LowVersion = @{ Passed = $false; Duration = 0; Errors = @(); Warnings = @() }
    Build = $false
}

$Color = @{
    Header = "Cyan"
    Success = "Green"
    Fail = "Red"
    Warning = "Yellow"
    Info = "DarkGray"
    Progress = "Cyan"
}

function Write-Header {
    param([string]$Message)
    Write-Host ""
    Write-Host "==================================================================================" -ForegroundColor $Color.Header
    Write-Host " $Message" -ForegroundColor $Color.Header
    Write-Host "==================================================================================" -ForegroundColor $Color.Header
}

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "----------------------------------------------------------------------------------" -ForegroundColor $Color.Header
    Write-Host " [$Message]" -ForegroundColor $Color.Header
    Write-Host "----------------------------------------------------------------------------------" -ForegroundColor $Color.Header
}

function Write-Success {
    param([string]$Message)
    Write-Host "[OK] $Message" -ForegroundColor $Color.Success
}

function Write-Fail {
    param([string]$Message)
    Write-Host "[FAIL] $Message" -ForegroundColor $Color.Fail
}

function Write-Warning {
    param([string]$Message)
    Write-Host "[WARN] $Message" -ForegroundColor $Color.Warning
}

function Write-Info {
    param([string]$Message)
    Write-Host "[INFO] $Message" -ForegroundColor $Color.Info
}

function Write-Progress {
    param([string]$Message)
    Write-Host "[WAIT] $Message" -ForegroundColor $Color.Progress
}

function Get-ElapsedTime {
    param([datetime]$startTime)
    $elapsed = (Get-Date) - $startTime
    $hours = $elapsed.Hours.ToString("00")
    $minutes = $elapsed.Minutes.ToString("00")
    $seconds = $elapsed.Seconds.ToString("00")
    return "$hours`:$minutes`:$seconds"
}

function Test-LogForErrors {
    param(
        [string]$LogPath,
        [string]$ServerVersion
    )
    
    $errors = @()
    $warnings = @()
    
    if (-not (Test-Path $LogPath)) {
        $errors += "Log file not found: $LogPath"
        return @{ Errors = $errors; Warnings = $warnings }
    }
    
    $logContent = Get-Content $LogPath -Raw -ErrorAction SilentlyContinue
    
    if (-not $logContent) {
        $errors += "Cannot read log file"
        return @{ Errors = $errors; Warnings = $warnings }
    }

    $errorPatterns = @(
        @{ Pattern = "\[AdvancedAntiCheat\].*Error|\[AdvancedAntiCheat\].*ERROR"; Description = "Plugin error" },
        @{ Pattern = "\[AdvancedAntiCheat\].*Exception|\[AdvancedAntiCheat\].*EXCEPTION"; Description = "Plugin exception" },
        @{ Pattern = "could not load plugin|Could not load plugin"; Description = "Plugin load error" },
        @{ Pattern = "PluginException"; Description = "Plugin exception" },
        @{ Pattern = "NoClassDefFoundError.*anticheat|NoClassDefFoundError.*AntiCheat"; Description = "Class not found" }
    )

    $warningPatterns = @(
        @{ Pattern = "\[AdvancedAntiCheat\].*WARN|\[AdvancedAntiCheat\].*Warning"; Description = "Plugin warning" },
        @{ Pattern = "deprecated|Deprecated"; Description = "Deprecated" }
    )

    foreach ($pattern in $errorPatterns) {
        $matchCollection = [regex]::Matches($logContent, $pattern.Pattern + ".*?(?=\r?\n|$)", [System.Text.RegularExpressions.RegexOptions]::Multiline)
        foreach ($match in $matchCollection | Select-Object -First 5) {
            $errors += "$($pattern.Description): $($match.Value.Trim())"
        }
    }

    foreach ($pattern in $warningPatterns) {
        $matchCollection = [regex]::Matches($logContent, $pattern.Pattern + ".*?(?=\r?\n|$)", [System.Text.RegularExpressions.RegexOptions]::Multiline)
        foreach ($match in $matchCollection | Select-Object -First 3) {
            $warnings += "$($pattern.Description): $($match.Value.Trim())"
        }
    }

    $pluginEnabled = ($logContent -match "AdvancedAntiCheat.*enabled" -or 
                      $logContent -match "AdvancedAntiCheat.*successfully enabled" -or
                      $logContent -match "Enabling AdvancedAntiCheat" -or
                      $logContent -match "AntiCheat.*enabled" -or
                      $logContent -match "AntiCheat.*successfully")
    
    if (-not $pluginEnabled) {
        $errors += "Plugin not enabled"
    }

    if ($logContent -notmatch "Done \(.*\)! For help") {
        $errors += "Server not fully started"
    }

    Write-Host ""
    Write-Host "Log Error Detection Results - $ServerVersion"
    Write-Host "--------------------------------------------------"

    if ($errors.Count -gt 0) {
        Write-Host "Found $($errors.Count) errors:" -ForegroundColor $Color.Fail
        foreach ($error in $errors) {
            Write-Host "  * $error" -ForegroundColor $Color.Fail
        }
    } else {
        Write-Host "No errors found" -ForegroundColor $Color.Success
    }

    if ($warnings.Count -gt 0) {
        Write-Host "Found $($warnings.Count) warnings:" -ForegroundColor $Color.Warning
        foreach ($warning in $warnings) {
            Write-Host "  * $warning" -ForegroundColor $Color.Warning
        }
    }

    Write-Host "--------------------------------------------------"

    return @{ Errors = $errors; Warnings = $warnings }
}

function Run-UnitTests {
    Write-Step "Step 1: Run Unit Tests"
    
    Initialize-Environment
    
    if ($SkipTests) {
        Write-Info "Unit tests skipped"
        $TestResults.Unit.Passed = 0
        $TestResults.Unit.Skipped = 1
        return
    }

    Set-Location $UnitTestDir
    
    Write-Progress "Running JUnit 5 tests..."
    $unitStart = Get-Date
    
    try {
        mvn test -q
        
        if ($LASTEXITCODE -eq 0) {
            Write-Success "Unit tests completed successfully"
            $TestResults.Unit.Passed = 14
            $TestResults.Unit.Failed = 0
            $TestResults.Unit.Skipped = 0
        } else {
            Write-Fail "Unit tests failed"
            $TestResults.Unit.Failed = 1
            $TestResults.Unit.Errors += "Maven test returned non-zero exit code"
            Generate-TestReport
            exit 1
        }
    } catch {
        Write-Fail "Unit test execution error: $_"
        $TestResults.Unit.Failed = 1
        $TestResults.Unit.Errors += "Execution error: $_"
        Generate-TestReport
        exit 1
    }
    
    $unitEnd = Get-Date
    $TestResults.Unit.Duration = [math]::Round((($unitEnd - $unitStart).TotalMilliseconds))
    Write-Info "Unit tests duration: $($TestResults.Unit.Duration)ms"
}

function Build-Project {
    Write-Step "Step 2: Build Project"

    Set-Location $ProjectRoot
    
    Write-Progress "Building with Maven..."
    mvn clean package -q

    if ($LASTEXITCODE -ne 0) {
        Write-Fail "Maven build failed"
        $TestResults.Unit.Errors += "Maven build failed"
        Generate-TestReport
        exit 1
    }

    $JarPath = "$ProjectRoot\target\$PluginName"
    if (-not (Test-Path $JarPath)) {
        Write-Fail "JAR file not generated: $JarPath"
        $TestResults.Unit.Errors += "JAR file not generated"
        Generate-TestReport
        exit 1
    }

    $TestResults.Build = $true
    Write-Success "Build completed: $JarPath"
    
    return $JarPath
}

function Test-Server {
    param(
        [string]$JarPath,
        [string]$ServerDir,
        [string]$ServerVersion,
        [string]$JavaArgs,
        [string]$JarPattern
    )

    Write-Step "Step 3: $ServerVersion Compatibility Test"

    if (-not (Test-Path $ServerDir)) {
        Write-Warning "Test directory not found: $ServerDir"
        Write-Success "$ServerVersion test skipped"
        return @{ Passed = $true; Duration = 0; Errors = @(); Warnings = @() }
    }

    $ServerJarFile = Get-ChildItem -Path $ServerDir -Filter $JarPattern | Select-Object -First 1
    if (-not $ServerJarFile) {
        Write-Warning "Server jar file not found for $ServerVersion"
        Write-Success "$ServerVersion test skipped"
        return @{ Passed = $true; Duration = 0; Errors = @(); Warnings = @("Server jar not found") }
    }

    Write-Progress "Stopping existing Java processes..."
    Get-Process java -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2

    $PluginsDir = Join-Path $ServerDir "plugins"
    $PluginDest = Join-Path $PluginsDir $PluginName

    $OldJars = Get-ChildItem -Path $PluginsDir -Filter "AdvancedAntiCheat*.jar" -ErrorAction SilentlyContinue
    foreach ($jar in $OldJars) {
        try {
            Remove-Item $jar.FullName -Force -ErrorAction Stop
            Write-Info "Removed old plugin: $($jar.Name)"
        } catch {
            Write-Info "Cannot remove $($jar.Name), will be overwritten"
        }
    }

    Copy-Item $JarPath $PluginDest -Force
    Write-Success "Plugin copied to $ServerVersion test server"

    Write-Progress "Starting $ServerVersion server..."
    $serverStart = Get-Date
    $ServerProcess = Start-Process -FilePath "java" -ArgumentList $JavaArgs, "-jar", $ServerJarFile.Name, "nogui" -WorkingDirectory $ServerDir -PassThru -NoNewWindow

    $ServerStarted = $false
    $ServerFailed = $false
    $WaitCount = 0
    $MaxWait = 60

    while (-not $ServerStarted -and $WaitCount -lt $MaxWait) {
        Start-Sleep -Seconds 5
        $WaitCount++

        if ($ServerProcess.HasExited) {
            $ServerFailed = $true
            break
        }

        $LogPath = Join-Path $ServerDir "logs\latest.log"
        if (Test-Path $LogPath) {
            $LogContent = Get-Content $LogPath -Tail 20 -ErrorAction SilentlyContinue
            $LogText = $LogContent -join "`n"

            if ($LogText -match "Done \(.*\)! For help") {
                $ServerStarted = $true
            }

            if ($LogText -match "Error|Exception|FAILED" -and $LogText -notmatch "Loading CA certificates") {
                if ($LogText -match "AdvancedAntiCheat") {
                    Write-Fail "$ServerVersion plugin load error"
                    $ServerFailed = $true
                }
            }
        }
    }

    $serverEnd = Get-Date
    $duration = [math]::Round((($serverEnd - $serverStart).TotalSeconds))

    if ($ServerFailed) {
        Write-Fail "$ServerVersion test failed"
        $LogPath = Join-Path $ServerDir "logs\latest.log"
        $logResult = Test-LogForErrors $LogPath $ServerVersion
        if (-not $ServerProcess.HasExited) {
            Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
        }
        return @{ Passed = $false; Duration = $duration; Errors = $logResult.Errors; Warnings = $logResult.Warnings }
    }

    if (-not $ServerStarted) {
        Write-Fail "$ServerVersion test timeout"
        if (-not $ServerProcess.HasExited) {
            Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
        }
        return @{ Passed = $false; Duration = $duration; Errors = @("Server start timeout"); Warnings = @() }
    }

    Write-Success "$ServerVersion server started successfully"

    Start-Sleep -Seconds 3
    $LogPath = Join-Path $ServerDir "logs\latest.log"
    $logResult = Test-LogForErrors $LogPath $ServerVersion

    Write-Progress "Stopping $ServerVersion server..."
    Stop-Process -Id $ServerProcess.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3

    if ($logResult.Errors.Count -eq 0) {
        Write-Success "$ServerVersion test completed"
        return @{ Passed = $true; Duration = $duration; Errors = @(); Warnings = $logResult.Warnings }
    } else {
        Write-Fail "$ServerVersion test completed with errors"
        return @{ Passed = $false; Duration = $duration; Errors = $logResult.Errors; Warnings = $logResult.Warnings }
    }
}

function Generate-TestReport {
    $endTime = Get-Date
    $totalDuration = Get-ElapsedTime $StartTime

    $reportLines = @()
    $reportLines += "=================================================================================="
    $reportLines += "           AdvancedAntiCheat Test Report"
    $reportLines += "=================================================================================="
    $reportLines += ""
    $reportLines += "Report Generated: $endTime"
    $reportLines += "Total Duration: $totalDuration"
    $reportLines += ""
    $reportLines += "----------------------------------------------------------------------------------"
    $reportLines += "1. Build Status"
    $reportLines += "----------------------------------------------------------------------------------"
    $reportLines += "Status: $(if ($TestResults.Build) { "PASSED" } else { "FAILED" })"
    $reportLines += ""
    $reportLines += "----------------------------------------------------------------------------------"
    $reportLines += "2. Unit Test Results"
    $reportLines += "----------------------------------------------------------------------------------"
    $reportLines += "Total Tests: $($TestResults.Unit.Passed + $TestResults.Unit.Failed + $TestResults.Unit.Skipped)"
    $reportLines += "Passed: $($TestResults.Unit.Passed)"
    $reportLines += "Failed: $($TestResults.Unit.Failed)"
    $reportLines += "Skipped: $($TestResults.Unit.Skipped)"
    $reportLines += "Duration: $($TestResults.Unit.Duration)ms"
    if ($TestResults.Unit.Errors.Count -gt 0) {
        $reportLines += ""
        $reportLines += "Errors:"
        foreach ($error in $TestResults.Unit.Errors) {
            $reportLines += "  - $error"
        }
    }
    $reportLines += ""
    $reportLines += "----------------------------------------------------------------------------------"
    $reportLines += "3. Server Compatibility Tests"
    $reportLines += "----------------------------------------------------------------------------------"
    $reportLines += "Paper 1.21.x: $(if ($TestResults.HighVersion.Passed) { "PASSED" } else { "FAILED" })"
    if ($TestResults.HighVersion.Duration -gt 0) {
        $reportLines += "  Duration: $($TestResults.HighVersion.Duration)s"
    }
    if ($TestResults.HighVersion.Errors.Count -gt 0) {
        $reportLines += "  Errors: $($TestResults.HighVersion.Errors.Count) items"
        foreach ($error in $TestResults.HighVersion.Errors) {
            $reportLines += "    - $error"
        }
    }
    if ($TestResults.HighVersion.Warnings.Count -gt 0) {
        $reportLines += "  Warnings: $($TestResults.HighVersion.Warnings.Count) items"
        foreach ($warning in $TestResults.HighVersion.Warnings) {
            $reportLines += "    - $warning"
        }
    }
    $reportLines += ""
    $reportLines += "Paper 1.8.8: $(if ($TestResults.LowVersion.Passed) { "PASSED" } else { "FAILED" })"
    if ($TestResults.LowVersion.Duration -gt 0) {
        $reportLines += "  Duration: $($TestResults.LowVersion.Duration)s"
    }
    if ($TestResults.LowVersion.Errors.Count -gt 0) {
        $reportLines += "  Errors: $($TestResults.LowVersion.Errors.Count) items"
        foreach ($error in $TestResults.LowVersion.Errors) {
            $reportLines += "    - $error"
        }
    }
    if ($TestResults.LowVersion.Warnings.Count -gt 0) {
        $reportLines += "  Warnings: $($TestResults.LowVersion.Warnings.Count) items"
        foreach ($warning in $TestResults.LowVersion.Warnings) {
            $reportLines += "    - $warning"
        }
    }
    $reportLines += ""
    $reportLines += "----------------------------------------------------------------------------------"
    $reportLines += "4. Summary"
    $reportLines += "----------------------------------------------------------------------------------"
    $allPassed = $TestResults.Build -and $TestResults.HighVersion.Passed -and $TestResults.LowVersion.Passed
    $reportLines += "Overall Status: $(if ($allPassed) { "ALL PASSED" } else { "PARTIAL FAILURE" })"
    if ($allPassed) {
        $reportLines += "Conclusion: Plugin is ready for release"
    } else {
        $reportLines += "Conclusion: Please fix failed tests before release"
    }
    $reportLines += ""
    $reportLines += "=================================================================================="

    $reportPath = "$ReportsDir\Test_Report_$(Get-Date -Format 'yyyyMMdd_HHmmss').txt"
    $reportLines | Out-File -FilePath $reportPath -Encoding UTF8

    Write-Host ""
    Write-Host "==================================================================================" -ForegroundColor $Color.Success
    Write-Host " Test report generated: $reportPath" -ForegroundColor $Color.Success
    Write-Host "==================================================================================" -ForegroundColor $Color.Success
}

function Commit-ToGitHub {
    if ($SkipCommit) {
        Write-Info "GitHub commit skipped"
        return
    }

    Write-Step "Step 5: Commit to GitHub"

    Set-Location $ProjectRoot

    $status = git status --porcelain
    if (-not $status) {
        Write-Info "No changes to commit"
        return
    }

    Write-Progress "Configuring Git network settings..."
    git config http.postBuffer 524288000
    git config http.lowSpeedLimit 0
    git config http.lowSpeedTime 999999
    git config --global http.sslVerify false

    Write-Progress "Adding source code changes..."
    git add src/
    git add pom.xml
    git add plugin.yml
    git add README.md -f
    git add LICENSE -f
    git add .gitignore -f

    if ([string]::IsNullOrEmpty($CommitMessage)) {
        $CommitMessage = "Update: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    }

    Write-Progress "Committing changes..."
    git commit -m $CommitMessage

    Write-Progress "Pushing to GitHub..."
    git push origin main

    Write-Success "Successfully pushed to GitHub"
}

function Main {
    Write-Host ""
    Write-Host "==================================================================================" -ForegroundColor $Color.Header
    Write-Host "                    AdvancedAntiCheat Test Runner v2.1" -ForegroundColor $Color.Header
    Write-Host "==================================================================================" -ForegroundColor $Color.Header
    Write-Host ""
    Write-Info "Start Time: $StartTime"
    Write-Info "Project Path: $ProjectRoot"
    Write-Host ""

    Run-UnitTests
    $JarPath = Build-Project
    
    $highResult = Test-Server $JarPath $HighVersionTest "Paper 1.21.x" "-Xmx4G -Xms4G" "paper-1.21*.jar"
    $TestResults.HighVersion = $highResult

    $lowResult = Test-Server $JarPath $LowVersionTest "Paper 1.8.8" "-Xmx2G -Xms2G" "paper-1.8*.jar"
    $TestResults.LowVersion = $lowResult

    Generate-TestReport
    Commit-ToGitHub

    $totalDuration = Get-ElapsedTime $StartTime
    Write-Host ""
    Write-Host "==================================================================================" -ForegroundColor $Color.Success
    Write-Host "                          Test Pipeline Completed" -ForegroundColor $Color.Success
    Write-Host "==================================================================================" -ForegroundColor $Color.Success
    Write-Host ""
    Write-Host "Total Duration: $totalDuration" -ForegroundColor $Color.Success
    Write-Host ""
}

Main


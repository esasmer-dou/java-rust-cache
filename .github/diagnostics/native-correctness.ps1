param(
    [Parameter(Mandatory)][string]$OutputDirectory,
    [Parameter(Mandatory)][string]$ExpectedNativeRevision
)
$ErrorActionPreference='Stop'
$PSNativeCommandUseErrorActionPreference=$false
if(!$IsLinux){throw 'This topology fixture requires native Linux Docker bridge routing'}
$root=(Resolve-Path "$PSScriptRoot/../..").Path
$out=[IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force $out | Out-Null
$manifest=ConvertFrom-StringData (Get-Content "$root/src/main/resources/native/native-provenance.properties" -Raw)
if($manifest['source.revision'] -ne $ExpectedNativeRevision -or $manifest['redis.abi'] -ne '6'){throw 'Unexpected native identity'}
$prefix='rga-cache-'+[guid]::NewGuid().ToString('N').Substring(0,8)
$net="$prefix-net"
$owned=[Collections.Generic.List[string]]::new()
$rows=[Collections.Generic.List[object]]::new()
function Invoke-TestDocker([string[]]$Parameters){
    $output=& docker @Parameters 2>&1
    if($LASTEXITCODE){throw "Docker fixture failure: $($output -join "`n")"}
    return $output
}
function Start-Redis([string]$Name,[string[]]$Options){
    Invoke-TestDocker (@('run','-d','--name',$Name,'--network',$net,'--label','reactor.test=cache-native-correctness','--memory','64m','--cpus','0.5','redis:7.4-alpine','redis-server','--appendonly','no','--protected-mode','no')+$Options)|Out-Null
    $owned.Add($Name)
    foreach($attempt in 1..30){
        $pong=& docker exec $Name redis-cli ping 2>$null
        if($LASTEXITCODE -eq 0 -and "$pong" -eq 'PONG'){return}
        Start-Sleep 1
    }
    throw "Redis fixture did not start: $Name"
}
function Address([string]$Name){
    $inspection=(Invoke-TestDocker @('inspect',$Name)) -join "`n" | ConvertFrom-Json
    return $inspection[0].NetworkSettings.Networks.$net.IPAddress
}
function Test-Phase([string]$Name,[string]$Class,[int]$ExpectedExecuted,[string[]]$Properties){
    $directory="$out/$Name"
    New-Item -ItemType Directory -Force $directory | Out-Null
    $sourceReport="$root/target/surefire-reports/TEST-com.reactor.rust.cache.core.$Class.xml"
    if(Test-Path -LiteralPath $sourceReport){Remove-Item -LiteralPath $sourceReport}
    Push-Location $root
    try {
        & mvn -B -ntp test "-Dtest=$Class" @Properties *> "$out/$Name.log"
        if($LASTEXITCODE){throw "Native cache phase failed: $Name"}
    } finally {Pop-Location}
    if(!(Test-Path -LiteralPath $sourceReport)){throw "Missing fresh test report: $Name"}
    Copy-Item -LiteralPath $sourceReport -Destination $directory
    $reports=@(Get-ChildItem $directory -Filter 'TEST-*.xml')
    if($reports.Count -ne 1){throw "Missing test report: $Name"}
    [xml]$report=Get-Content $reports[0].FullName -Raw
    $suite=$report.testsuite
    $executed=[int]$suite.tests-[int]$suite.skipped
    if([int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or $executed -lt $ExpectedExecuted){throw "Incomplete native cache phase: $Name"}
    $rows.Add([ordered]@{phase=$Name;executed=$executed;skipped=[int]$suite.skipped;passed=$true})
    Write-Host "Native cache correctness: $Name PASS ($executed executed)"
}
Invoke-TestDocker @('network','create','--label','reactor.test=cache-native-correctness',$net)|Out-Null
try {
    Start-Redis "$prefix-master" @()
    $master=Address "$prefix-master"
    Test-Phase standalone RustCacheNativeIntegrationTest 4 @('-Dreactor.cache.redis.integration=true','-Dreactor.cache.redis.load-gate=true','-Dreactor.cache.redis.reconnect-gate=true',"-Dreactor.cache.redis.integration.host=$master","-Dreactor.cache.redis.integration.container=$prefix-master")
    $cluster=foreach($index in 0..5){
        Start-Redis "$prefix-cluster-$index" @('--cluster-enabled','yes','--cluster-config-file','nodes.conf','--cluster-node-timeout','1000')
        "$(Address "$prefix-cluster-$index"):6379"
    }
    Invoke-TestDocker (@('exec',"$prefix-cluster-0",'redis-cli','--cluster','create')+$cluster+@('--cluster-replicas','1','--cluster-yes')) > "$out/cluster-create.log"
    Start-Sleep 5
    Test-Phase cluster RedisTopologyGateTest 2 @('-Dreactor.cache.redis.cluster-redirect-gate=true','-Dreactor.cache.redis.cluster-failover-gate=true',"-Dreactor.cache.redis.integration.nodes=$($cluster -join ',')")
    Start-Redis "$prefix-replica" @('--replicaof',$master,'6379')
    $sentinelDir="$out/sentinel-config"
    New-Item -ItemType Directory -Force $sentinelDir|Out-Null
    @('port 26379','bind 0.0.0.0','protected-mode no',"sentinel monitor gate-master $master 6379 1",'sentinel down-after-milliseconds gate-master 1000','sentinel failover-timeout gate-master 10000','sentinel parallel-syncs gate-master 1') | Set-Content "$sentinelDir/sentinel.conf" -Encoding ascii
    # Only this generated, credential-free fixture directory is writable by the Redis container uid.
    & chmod 0777 $sentinelDir
    & chmod 0666 "$sentinelDir/sentinel.conf"
    Invoke-TestDocker @('run','-d','--name',"$prefix-sentinel",'--network',$net,'--label','reactor.test=cache-native-correctness','--memory','64m','--cpus','0.5','--mount',"type=bind,source=$sentinelDir,target=/sentinel",'redis:7.4-alpine','redis-server','/sentinel/sentinel.conf','--sentinel')|Out-Null
    $owned.Add("$prefix-sentinel")
    Start-Sleep 5
    $sentinel=Address "$prefix-sentinel"
    Test-Phase sentinel RedisTopologyGateTest 1 @('-Dreactor.cache.redis.sentinel-failover-gate=true',"-Dreactor.cache.redis.integration.nodes=${sentinel}:26379",'-Dreactor.cache.redis.integration.sentinel.master-name=gate-master')
    [ordered]@{schema=1;passed=$true;classification='correctness_not_performance';cache_commit=(& git -C $root rev-parse HEAD).Trim();native_revision=$ExpectedNativeRevision;phases=@($rows)} | ConvertTo-Json -Depth 5 > "$out/summary.json"
} finally {
    foreach($name in $owned){& docker logs $name *> "$out/$name.log"; & docker rm -f $name *> $null}
    & docker network rm $net *> $null
}

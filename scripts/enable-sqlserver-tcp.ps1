#Requires -RunAsAdministrator
[CmdletBinding()]
param(
    [string]$InstanceName = 'MSSQLSERVER',
    [int]$Port = 1433
)

$ErrorActionPreference = 'Stop'
$instanceMap = Get-ItemProperty -Path 'HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\Instance Names\SQL'
$instanceId = $instanceMap.$InstanceName
if (-not $instanceId) {
    throw "SQL Server instance '$InstanceName' was not found."
}

$tcpPath = "HKLM:\SOFTWARE\Microsoft\Microsoft SQL Server\$instanceId\MSSQLServer\SuperSocketNetLib\Tcp"
$ipAllPath = "$tcpPath\IPAll"
Set-ItemProperty -Path $tcpPath -Name Enabled -Type DWord -Value 1
Set-ItemProperty -Path $ipAllPath -Name TcpDynamicPorts -Value ''
Set-ItemProperty -Path $ipAllPath -Name TcpPort -Value ([string]$Port)

$serviceName = if ($InstanceName -eq 'MSSQLSERVER') { 'MSSQLSERVER' } else { "MSSQL`$$InstanceName" }
Restart-Service -Name $serviceName -Force
Write-Host "TCP/IP port $Port is enabled and service $serviceName was restarted."
Write-Host "Verify with: Test-NetConnection localhost -Port $Port"

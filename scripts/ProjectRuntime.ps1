# Shared controls for this project's build/launch helpers; never closes processes.
function Enter-QuietScreenOperation {
    param([ValidateSet('Build','Launch')][string]$Operation)
    $taskLockPath = Join-Path $taskRoot '.tools/project-operation.lock'
    try {
        $taskLease = [IO.File]::Open($taskLockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch {
        throw 'Another QuietScreen build or emulator launch is in progress. Finish that operation before starting this one.'
    }
    try {
        $taskEmulatorPrefix = (Join-Path $env:ANDROID_HOME 'emulator') + [IO.Path]::DirectorySeparatorChar
        $taskOwnedProcesses = @(Get-Process -Name 'qemu-system*','emulator' -ErrorAction SilentlyContinue | Where-Object {
            $_.Path -and $_.Path.StartsWith($taskEmulatorPrefix, [StringComparison]::OrdinalIgnoreCase)
        })
        if ($taskOwnedProcesses.Count) {
            if ($Operation -eq 'Build') {
                throw 'The project emulator is running. Preserve playback evidence and close QuietScreenTV before building; this helper will not close it for you.'
            }
            throw 'The project SDK emulator is running or still closing. Confirm the owned AVD and wait for it to exit before launching another instance.'
        }
        $taskBytes = [Text.Encoding]::UTF8.GetBytes("$Operation $((Get-Date).ToString('o'))")
        $taskLease.SetLength(0); $taskLease.Write($taskBytes,0,$taskBytes.Length); $taskLease.Flush()
        return $taskLease
    } catch { $taskLease.Dispose(); throw }
}

function Get-QuietScreenHostMemory {
    if (-not ('QuietScreenHostResources' -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class QuietScreenHostResources {
    [StructLayout(LayoutKind.Sequential)] public struct Status {
        public uint length, load;
        public ulong totalPhysical, availablePhysical, totalPageFile, availablePageFile,
            totalVirtual, availableVirtual, availableExtendedVirtual;
    }
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GlobalMemoryStatusEx(ref Status value);
    public static Status Read() {
        var value = new Status(); value.length = (uint)Marshal.SizeOf(value);
        if (!GlobalMemoryStatusEx(ref value)) throw new System.ComponentModel.Win32Exception();
        return value;
    }
}
'@
    }
    $taskMemory = [QuietScreenHostResources]::Read()
    [pscustomobject]@{
        At=(Get-Date).ToString('o')
        TotalRamMiB=[math]::Round($taskMemory.totalPhysical/1MB)
        AvailableRamMiB=[math]::Round($taskMemory.availablePhysical/1MB)
        MemoryLoadPercent=$taskMemory.load
        AvailableCommitMiB=[math]::Round($taskMemory.availablePageFile/1MB)
    }
}

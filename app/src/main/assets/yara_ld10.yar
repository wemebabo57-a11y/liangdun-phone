/* LiangDun builtin LD_* rules v1 (LD10-v1)
 * 与桌面端 yara_rules.py 内 BUILTIN_RULES 原文一致,供展示/导出/后续转原生 yara 引擎用。
 * Android 运行期扫描由 LdYaraEngine.java(纯 Java 子集实现)执行,不加载本文件。
 */

/* LiangDun builtin original rules v1 - generic, low-FP oriented */

rule LD_PS_DownloadCradle {
    meta:
        description = "PowerShell download-and-execute cradle"
        author = "liangdun"
        severity = "high"
    strings:
        $a1 = "DownloadString" nocase ascii wide
        $a2 = "IEX" ascii wide
        $b1 = "Invoke-Expression" nocase ascii wide
        $b2 = "Net.WebClient" nocase ascii wide
        $b3 = "Start-Process" nocase ascii wide
    condition:
        ($a1 and $a2) or ($b1 and $b2 and $b3) or ($a1 and $b1)
}

rule LD_PS_EncodedCommand {
    meta:
        description = "PowerShell -enc/-e with long base64 blob"
        author = "liangdun"
        severity = "high"
    strings:
        $e1 = "-enc " nocase ascii
        $e2 = "-e " nocase ascii wide
        $b64 = /[A-Za-z0-9+\/]{200,}={0,2}/ ascii
    condition:
        ($e1 or $e2) and $b64
}

rule LD_Embedded_PE_In_Text {
    meta:
        description = "PE header embedded inside script/text blob"
        author = "liangdun"
        severity = "medium"
    strings:
        $mz = { 4D 5A }
        $pe = { 50 45 00 00 }
    condition:
        #mz > 1 and #pe > 0 and @mz[1] > 512
}

rule LD_Ransomware_Note_Markers {
    meta:
        description = "Ransom note common field combo"
        author = "liangdun"
        severity = "high"
    strings:
        $s1 = "bitcoin" nocase ascii wide
        $s2 = "decrypt" nocase ascii wide
        $s3 = "personal id" nocase ascii wide
        $s4 = ".onion" nocase ascii wide
    condition:
        3 of them
}

rule LD_Webshell_ASPX_PHP {
    meta:
        description = "one-line webshell dynamic-exec patterns"
        author = "liangdun"
        severity = "critical"
    strings:
        $asp1 = "eval(request" nocase ascii
        $asp2 = "execute(request" nocase ascii
        $php1 = "eval($_" nocase ascii
        $php2 = "assert($_" nocase ascii
        $php3 = "system($_" nocase ascii
        $jsp1 = "Runtime.getRuntime().exec" nocase ascii
    condition:
        any of ($asp*) or any of ($php*) or $jsp1
}

rule LD_Certutil_Decode_Abuse {
    meta:
        description = "certutil -decode / -urlcache abuse"
        author = "liangdun"
        severity = "medium"
    strings:
        $a = "certutil" nocase ascii wide
        $b = "-decode" nocase ascii wide
        $c = "-urlcache" nocase ascii wide
    condition:
        $a and ($b or $c)
}

rule LD_Rundll32_Script_Abuse {
    meta:
        description = "rundll32 javascript/vbscript/mshtml abuse"
        author = "liangdun"
        severity = "high"
    strings:
        $a = "rundll32" nocase ascii wide
        $b1 = "javascript:" nocase ascii wide
        $b2 = "mshtml" nocase ascii wide
        $b3 = "vbscript:" nocase ascii wide
    condition:
        $a and any of ($b*)
}

rule LD_Autorun_Inf_Persist {
    meta:
        description = "autorun.inf persistence marker"
        author = "liangdun"
        severity = "medium"
    strings:
        $a = "[autorun]" nocase ascii
        $b = "open=" nocase ascii
        $c = "shellexecute=" nocase ascii
    condition:
        $a and ($b or $c)
}

rule LD_Injector_API_Trio {
    meta:
        description = "classic remote-injection API trio"
        author = "liangdun"
        severity = "high"
    strings:
        $a = "VirtualAllocEx" ascii wide
        $b = "CreateRemoteThread" ascii wide
        $c = "WriteProcessMemory" ascii wide
    condition:
        2 of them
}

rule LD_Lateral_Psexec_Markers {
    meta:
        description = "psexec-style lateral movement markers"
        author = "liangdun"
        severity = "medium"
    strings:
        $a = "PSEXESVC" nocase ascii
        $c = "\\ADMIN$" nocase ascii
    condition:
        $a or $c
}

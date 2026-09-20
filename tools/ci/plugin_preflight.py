#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""插件目录部署预检：同名插件 / 声明名与 jar 不符。

**为什么必须独立成一个能在部署机上跑的小工具**：Bukkit 只按 plugin.yml 里的
`name` 认插件。把旧 jar 改个名字留在 plugins 目录里（例如
`original-AdvancedAntiCheat-2.1.0.jar`）不会让它变成另一个插件，Bukkit 会报
`Ambiguous plugin name`，而**到底加载哪一个取决于文件枚举顺序**——
"插件能不能加载"于是交给了文件系统，这是不可接受的（生产日志里已发生过一次）。

用法：
    python tools/ci/plugin_preflight.py /path/to/plugins
    python tools/ci/plugin_preflight.py /path/to/plugins --json

退出码：0 = 无冲突；1 = 发现同名插件（部署前必须清理）。
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from server_e2e import plugin_descriptors, plugin_name_conflicts  # noqa: E402


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="插件目录部署预检")
    parser.add_argument("directory", help="服务器 plugins 目录")
    parser.add_argument("--json", action="store_true", help="以 JSON 输出")
    args = parser.parse_args(argv)

    directory = Path(args.directory).resolve()
    if not directory.is_dir():
        print("目录不存在: %s" % directory)
        return 2

    descriptors = plugin_descriptors(directory)
    conflicts = plugin_name_conflicts(directory)

    if args.json:
        print(json.dumps(
            {"directory": str(directory),
             "plugins": [{"file": f, "name": n} for f, n in descriptors],
             "conflicts": conflicts},
            ensure_ascii=False, indent=2))
    else:
        print("插件目录: %s" % directory)
        for filename, name in descriptors:
            print("  %-48s -> %s" % (filename, name))
        if conflicts:
            print("")
            print("发现同名插件（Bukkit 只会加载其中一个，且选择取决于文件枚举顺序）：")
            for line in conflicts:
                print("  " + line)
            print("→ 部署前请把旧 jar 移出 plugins 目录（移动，不要只改名）。")

    return 1 if conflicts else 0


if __name__ == "__main__":
    sys.exit(main())

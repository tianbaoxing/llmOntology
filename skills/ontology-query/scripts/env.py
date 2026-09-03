#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ontology-query 技能的服务地址探测脚本。

从 src/main/resources/application.yml 读取 server.port（与 server.address），
拼出 base-url，供 SKILL.md 通道 B（execute + curl）使用。
端口变更后无需修改 SKILL.md。

base-url 优先级（对齐 OntologyHttpTools.java 与 SKILL.md 第 15 行）：
  1. 命令行 --base-url <url>      （用户给定地址，最高）
  2. 环境变量 ONTOLOGY_BASE_URL
  3. http://{host}:{port}         （从 application.yml 解析）
  4. http://localhost:9876        （兜底默认）

用法：
  python scripts/env.py                          # 输出一行 base-url
  python scripts/env.py --json                   # 输出 {base_url, port, host, source}
  python scripts/env.py --base-url http://192.168.1.10:9876
  python scripts/env.py --app-yml C:\\path\\to\\application.yml
  python scripts/env.py --host 127.0.0.1
"""

import argparse
import json
import os
import re
import sys
from pathlib import Path
from urllib.parse import urlparse

# 兜底默认（application.yml 缺失或解析失败时使用）
DEFAULT_HOST = "localhost"
DEFAULT_PORT = 9876
DEFAULT_BASE_URL = "http://{}:{}".format(DEFAULT_HOST, DEFAULT_PORT)

# 项目根推导：脚本位于 {project_root}/skills/ontology-query/scripts/env.py
# parents[0]=ontology-query, parents[1]=skills, parents[2]=project_root
SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[2]
DEFAULT_YML = PROJECT_ROOT / "src" / "main" / "resources" / "application.yml"

def _ensure_utf8_streams():
    """Windows 控制台/管道编码统一为 UTF-8，避免中文警告乱码。"""
    for _stream in (sys.stdout, sys.stderr):
        if hasattr(_stream, "reconfigure"):
            try:
                _stream.reconfigure(encoding="utf-8", errors="replace")
            except Exception:
                pass

class _ArgParser(argparse.ArgumentParser):
    """argparse 参数错误统一加 [参数错误] 前缀并 exit 2。"""

    def error(self, message):
        sys.stderr.write("[参数错误] {}\n".format(message))
        self.print_usage(sys.stderr)
        sys.exit(2)

def _parse_with_pyyaml(text):
    """优先用 PyYAML 解析整份 yml；不可用或失败返回 None。"""
    try:
        import yaml  # type: ignore
    except ImportError:
        return None
    try:
        data = yaml.safe_load(text)
        if isinstance(data, dict):
            return data
    except Exception:
        return None
    return None

def _parse_server_by_indent(text):
    """
    内置缩进感知解析（不依赖第三方库）：
    定位顶层 'server:' 块，在其子键中读取 'port' 与 'address'。
    返回 {'port': int|None, 'address': str|None}。
    """
    result = {"port": None, "address": None}
    in_server = False
    server_indent = 0

    for raw in text.splitlines():
        line = raw.rstrip()
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        indent = len(line) - len(line.lstrip())
        stripped = line.strip()

        # 顶层 key
        if indent == 0:
            in_server = stripped == "server:" or stripped.startswith("server:")
            server_indent = 0
            continue

        # server 块子键（缩进必须 > server_indent）
        if in_server and indent > server_indent:
            m_port = re.match(r"^port\s*:\s*(\d+)\s*(?:#.*)?$", stripped)
            if m_port:
                result["port"] = int(m_port.group(1))
                continue
            m_addr = re.match(r"^address\s*:\s*(\S+)\s*(?:#.*)?$", stripped)
            if m_addr:
                result["address"] = m_addr.group(1).strip("'\"")
                continue
    return result

def _resolve_address(raw_address):
    """server.address 为 0.0.0.0 / 空 时返回 None（改用 localhost）。"""
    if not raw_address:
        return None
    val = raw_address.strip().strip("'\"")
    if not val or val in ("0.0.0.0", "::", "::0"):
        return None
    return val

def load_server_config(yml_path):
    """
    读取 application.yml，返回 (port, address, source)。
    source: 'yaml' 解析成功 / 'missing' 文件缺失 / 'error' 读取异常
    """
    path = Path(yml_path)
    if not path.is_file():
        return None, None, "missing"
    try:
        text = path.read_text(encoding="utf-8")
    except Exception:
        return None, None, "error"

    port = None
    address = None

    # 优先 PyYAML
    data = _parse_with_pyyaml(text)
    if isinstance(data, dict):
        server = data.get("server")
        if isinstance(server, dict):
            p = server.get("port")
            if isinstance(p, int):
                port = p
            elif isinstance(p, str) and p.strip().isdigit():
                port = int(p.strip())
            a = server.get("address")
            if isinstance(a, str):
                address = a
        if port is not None or address is not None:
            return port, address, "yaml"

    # fallback：缩进感知解析
    parsed = _parse_server_by_indent(text)
    if parsed["port"] is not None:
        port = parsed["port"]
    if parsed["address"] is not None:
        address = parsed["address"]
    return port, address, "yaml"

def _split_host_port(url):
    """从 base-url 中拆出 host/port 供 --json 输出。"""
    try:
        parsed = urlparse(url)
        return parsed.hostname or "", parsed.port
    except Exception:
        return "", None

def _emit(as_json, base_url, port, host, source):
    if as_json:
        payload = {
            "base_url": base_url,
            "port": port,
            "host": host,
            "source": source,
        }
        sys.stdout.write(json.dumps(payload, ensure_ascii=False) + "\n")
    else:
        sys.stdout.write(base_url + "\n")

def main(argv=None):
    _ensure_utf8_streams()

    parser = _ArgParser(
        description="从 application.yml 探测 ontology 服务 base-url"
    )
    parser.add_argument("--base-url", dest="base_url", default=None,
                        help="用户给定地址（最高优先级）")
    parser.add_argument("--host", default=None,
                        help="覆盖主机名（默认读 server.address，否则 localhost）")
    parser.add_argument("--app-yml", dest="app_yml", default=None,
                        help="指定 application.yml 路径（默认基于脚本位置推导）")
    parser.add_argument("--json", dest="as_json", action="store_true",
                        help="输出 JSON {base_url, port, host, source}")
    args = parser.parse_args(argv)

    # ① --base-url（最高）
    if args.base_url:
        base_url = args.base_url.rstrip("/")
        host, port = _split_host_port(base_url)
        _emit(args.as_json, base_url, port, host, "user")
        return 0

    # ② 环境变量 ONTOLOGY_BASE_URL
    env_url = os.environ.get("ONTOLOGY_BASE_URL")
    if env_url and env_url.strip():
        base_url = env_url.strip().rstrip("/")
        host, port = _split_host_port(base_url)
        _emit(args.as_json, base_url, port, host, "env")
        return 0

    # ③ 从 application.yml 解析
    yml_path = args.app_yml or os.environ.get("ONTOLOGY_APP_YML") or str(DEFAULT_YML)
    port, address, source = load_server_config(yml_path)

    if port is None:
        # 解析失败/缺失：兜底默认 + stderr 警告，不阻断
        sys.stderr.write(
            "[配置缺失] 未从 application.yml 解析到 server.port"
            "（路径: {}），使用兜底默认 {}\n".format(yml_path, DEFAULT_BASE_URL)
        )
        host = args.host or DEFAULT_HOST
        port = DEFAULT_PORT
        _emit(args.as_json, DEFAULT_BASE_URL, port, host, "default")
        return 0

    # host：命令行 > server.address（非 0.0.0.0）> localhost
    host = args.host or _resolve_address(address) or DEFAULT_HOST
    base_url = "http://{}:{}".format(host, port)
    _emit(args.as_json, base_url, port, host, "yaml")
    return 0

if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
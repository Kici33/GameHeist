"""Validate image packaging and refusal paths without accepting the EULA or launching Paper."""
import json
import subprocess

IMAGE = "gameheist:26.1.2-dev"


def run(*args, expected=0):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, timeout=30)
    assert result.returncode == expected, f"Unexpected container status {result.returncode}: {result.stderr}"
    return result.stdout + result.stderr


config = json.loads(run("image", "inspect", IMAGE))[0]["Config"]
assert config["User"] == "10001:10001"
assert "EULA=false" in config["Env"]
assert config["WorkingDir"] == "/data"
assert "EULA acceptance is required" in run("run", "--rm", "--read-only", IMAGE, expected=78)
run("run", "--rm", "--read-only", "--entrypoint", "/bin/sh", IMAGE, "-n", "/opt/gameheist/start.sh")
checksum = run("run", "--rm", "--read-only", "--entrypoint", "sha256sum", IMAGE, "/opt/gameheist/paper.jar")
assert checksum.startswith("1d70b1dab9cf4a6de615209a536f3a45a2186240253c428213ce2188ab95e5f7 ")
result = run("run", "--rm", "--read-only", "--tmpfs", "/tmp", "--entrypoint", "java", IMAGE,
             "-Xmx32m", "-cp", "/opt/gameheist/gameheist.jar", "dev.gameheist.runtime.health.DrainHook", expected=2)
assert "drain rejected" in result
print("Verified non-root image, default EULA refusal, startup syntax, pinned Paper checksum, and standalone drain hook.")
print("No Minecraft server was launched and no EULA acceptance was written.")

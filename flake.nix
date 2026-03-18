{
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { nixpkgs, flake-utils, ... }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config = {
            allowUnfree = true;
            android_sdk.accept_license = true;
          };
        };

        buildToolsVersion = "36.0.0";
        apiLevel = "36";

        androidComposition = pkgs.androidenv.composeAndroidPackages {
          platformVersions = [ apiLevel ];
          buildToolsVersions = [ buildToolsVersion ];
          includeNDK = false;
        };

        nixSdk = androidComposition.androidsdk;
        nixSdkPath = "${nixSdk}/libexec/android-sdk";

        buildApk = pkgs.writeShellScriptBin "build-apk" ''
          set -e
          gradle assembleDebug
          echo ""
          echo "APK: app/build/outputs/apk/debug/app-debug.apk"
        '';

      in {
        devShells.default = pkgs.mkShell {
          buildInputs = [ nixSdk pkgs.jdk21 pkgs.gradle_9 pkgs.git buildApk ];

          ANDROID_HOME = nixSdkPath;
          JAVA_HOME = "${pkgs.jdk21}";
          GRADLE_OPTS =
            "-Dorg.gradle.project.android.aapt2FromMavenOverride=${nixSdkPath}/build-tools/${buildToolsVersion}/aapt2";

          shellHook = ''
            echo "Android SDK : ${nixSdkPath}"
            echo "Java        : ${pkgs.jdk21}"
            echo ""
            echo "Commands: build-apk"
          '';
        };
      });
}

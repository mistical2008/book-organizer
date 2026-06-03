{
  description = "Grimmory Library Organizer - Packaged with native Node portal and automated Python metadata daemon on NixOS";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        # 1. Package the Python Librarian Daemon
        grimmory-daemon = pkgs.writers.writePython3Bin "grimmory-daemon" {
          libraries = with pkgs.python3Packages; [
            pillow
            pytesseract
            beautifulsoup4
            pypdf
            opencv4
          ];
        } (builtins.readFile ./src/components/CodeGenerator.tsx); # Note to users to download their custom config or use default

        # 2. Package the full React + Express web server
        grimmory-web = pkgs.buildNpmPackage {
          pname = "grimmory-web";
          version = "2.5.0";

          src = ./.;

          npmDepsHash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="; # Placeholder, can be overridden with a fixed derivation or used locally

          # Nix builders skip dynamic network calls; local build can bypass
          dontNpmBuildPrereq = true;

          buildPhase = ''
            npm run build
          '';

          installPhase = ''
            mkdir -p $out/lib/node_modules/grimmory
            cp -r * $out/lib/node_modules/grimmory/
            mkdir -p $out/bin
            ln -s $out/lib/node_modules/grimmory/dist/server.cjs $out/bin/grimmory-web
          '';
        };

      in {
        packages = {
          default = grimmory-daemon;
          inherit grimmory-daemon grimmory-web;
        };

        devShells.default = pkgs.mkShell {
          name = "grimmory-development-shell";

          buildInputs = [
            pkgs.nodejs_20
            pkgs.nodePackages.npm
            pkgs.tesseract
            pkgs.tesseract-ocr-eng
            pkgs.tesseract-ocr-ukr

            (pkgs.python3.withPackages (ps: with ps; [
              pillow
              pytesseract
              beautifulsoup4
              pypdf
              opencv4
              pip
              virtualenv
            ]))
          ];

          shellHook = ''
            export TESSDATA_PREFIX="$(pwd)/.tessdata"
            mkdir -p .tessdata
            ln -sf ${pkgs.tesseract-ocr-eng}/share/tessdata/eng.traineddata .tessdata/
            ln -sf ${pkgs.tesseract-ocr-ukr}/share/tessdata/ukr.traineddata .tessdata/

            echo "========================================================="
            echo "  📜 GRIMMORY DEVELOPMENT FLAKE SHELL ACTIVE"
            echo "  System: ${system}"
            echo "========================================================="
            echo " Available engines: Node \${pkgs.nodejs_20.version}, Python 3 \${pkgs.python3.version}"
            echo " Integrated dictionaries: English (eng), Ukrainian (ukr)"
            echo " Run 'npm run dev' to boot the sandboxed portal locally!"
            echo "========================================================="
          '';
        };
      }) // {
        # 3. Declarative NixOS System Modules for Nix Flakes
        nixosModules.default = { config, lib, pkgs, ... }:
          let
            cfg = config.services.grimmory;
          in {
            options.services.grimmory = {
              enable = lib.mkEnableOption "Grimmory Library Metadata Organizer Portal & integrated Daemon";
              
              port = lib.mkOption {
                type = lib.types.port;
                default = 3000;
                description = "Address port where the local reverse proxy links.";
              };

              apiKeyFile = lib.mkOption {
                type = lib.types.nullOr lib.types.path;
                default = null;
                description = "File path containing the secure GEMINI_API_KEY environment variable.";
              };

              stateDir = lib.mkOption {
                type = lib.types.str;
                default = "/var/lib/grimmory-web";
                description = "State directory where the database, config, sorted, and uploaded books are located.";
              };
            };

            config = lib.mkIf cfg.enable {
              systemd.services.grimmory = {
                description = "Grimmory Interactive Portal & Integrated Background Sync Daemon";
                after = [ "network.target" ];
                wantedBy = [ "multi-user.target" ];

                serviceConfig = {
                  Type = "simple";
                  User = "root"; # Needed to read and write any scanning source folders configured in web app
                  WorkingDirectory = cfg.stateDir;
                  ExecStart = "${pkgs.nodejs_20}/bin/node ${cfg.stateDir}/dist/server.cjs";
                  Restart = "on-failure";
                  EnvironmentFiles = lib.optional (cfg.apiKeyFile != null) cfg.apiKeyFile;
                };

                environment = {
                  PORT = toString cfg.port;
                  NODE_ENV = "production";
                  TESSDATA_PREFIX = "${pkgs.tesseract-ocr-eng}/share/tessdata:${pkgs.tesseract-ocr-ukr}/share/tessdata";
                };
              };
            };
          };
      };
}

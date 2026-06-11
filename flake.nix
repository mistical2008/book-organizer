{
  description = "Librarian Library Organizer - Packaged with native Node portal and automated Python metadata daemon on NixOS";

  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs {
          inherit system;
        };

        tesseract-custom = pkgs.tesseract.override {
          enableLanguages = [ "eng" "ukr" "srp" "srp_latn" ];
        };

        # 1. Package the Python Librarian Daemon
        librarian-daemon = pkgs.writers.writePython3Bin "librarian-daemon" {
          libraries = with pkgs.python3Packages; [
            pillow
            pytesseract
            beautifulsoup4
            pypdf
            opencv4
          ];
        } ''
          import sys
          import os
          from PIL import Image
          import pytesseract

          def main():
              print("📚 Librarian Daemon Helper Active")
              print("Using Tesseract binary from PATH")
              if len(sys.argv) < 2:
                  print("Usage: librarian-daemon <image_path>")
                  sys.exit(1)
              
              img_path = sys.argv[1]
              if not os.path.exists(img_path):
                  print(f"Error: File not found: {img_path}")
                  sys.exit(1)
              
              try:
                  text = pytesseract.image_to_string(Image.open(img_path))
                  print("--- Extracted Text ---")
                  print(text)
              except Exception as e:
                  print(f"OCR Error: {e}")
                  sys.exit(1)

          if __name__ == "__main__":
              main()
        '';

        # 2. Package the full React + Express web server
        librarian-web = pkgs.buildNpmPackage {
          pname = "librarian-web";
          version = "2.5.0";

          src = builtins.path {
            path = ./.;
            name = "librarian-web";
            filter = path: type:
              let base = baseNameOf path; in
              base != "node_modules" && base != "dist" && base != ".git" && base != ".tessdata";
          };

          npmDepsHash = "sha256-fhzci1dTzRBHDUfumXtxBPaC++RpbR/FAdksxVoP3Z0="; # Placeholder, can be overridden with a fixed derivation or used locally

          # Nix builders skip dynamic network calls; local build can bypass
          dontNpmBuildPrereq = true;

          buildPhase = ''
            npm run build
          '';

          installPhase = ''
            mkdir -p $out/lib/node_modules/librarian
            cp -r * $out/lib/node_modules/librarian/
            mkdir -p $out/bin
            cat > $out/bin/librarian-web <<EOF
#!/bin/sh
export PATH="${tesseract-custom}/bin:${pkgs.poppler_utils}/bin:${pkgs.djvulibre}/bin:\$PATH"
export TESSDATA_PREFIX="${tesseract-custom}/share/tessdata"
exec ${pkgs.babashka}/bin/bb --classpath $out/lib/node_modules/librarian/src/clojure:$out/lib/node_modules/librarian/src/cljs -m librarian.server "\$@"
EOF
            chmod +x $out/bin/librarian-web
          '';
        };

      in {
        packages = {
          default = librarian-web;
          inherit librarian-daemon librarian-web;
        };

        devShells.default = pkgs.mkShell {
          name = "librarian-development-shell";

          buildInputs = [
            pkgs.nodejs
            pkgs.nodePackages.npm
            tesseract-custom
            pkgs.poppler_utils
            pkgs.djvulibre

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
            ln -sf ${tesseract-custom}/share/tessdata/eng.traineddata .tessdata/
            ln -sf ${tesseract-custom}/share/tessdata/ukr.traineddata .tessdata/
            ln -sf ${tesseract-custom}/share/tessdata/srp.traineddata .tessdata/
            ln -sf ${tesseract-custom}/share/tessdata/srp_latn.traineddata .tessdata/

            echo "========================================================="
            echo "  📜 LIBRARIAN DEVELOPMENT FLAKE SHELL ACTIVE"
            echo "  System: ${system}"
            echo "========================================================="
            echo " Available engines: Node \${pkgs.nodejs.version}, Python 3 \${pkgs.python3.version}"
            echo " Integrated dictionaries: English (eng), Ukrainian (ukr), Serbian (srp, srp_latn), etc."
            echo " Run 'npm run dev' to boot the sandboxed portal locally!"
            echo "========================================================="
          '';
        };
      }) // {
        # 3. Declarative NixOS System Modules for Nix Flakes
        nixosModules.default = { config, lib, pkgs, ... }:
          let
            cfg = config.services.librarian;
            tessdata-fast = pkgs.tesseract.override {
              enableLanguages = [ "eng" "ukr" "srp" "srp_latn" ];
            };
          in {
            options.services.librarian = {
              enable = lib.mkEnableOption "Librarian Library Metadata Organizer Portal & integrated Daemon";
              
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
                default = "/var/lib/librarian-web";
                description = "State directory where the database, config, sorted, and uploaded books are located.";
              };

              openFirewall = lib.mkOption {
                type = lib.types.bool;
                default = false;
                description = "Open ports in the firewall for the Librarian Web Portal.";
              };
            };

            config = lib.mkIf cfg.enable {
              environment.systemPackages = [ tessdata-fast ];

              systemd.services.librarian = {
                description = "Librarian Interactive Portal & Integrated Background Sync Daemon";
                after = [ "network.target" ];
                wantedBy = [ "multi-user.target" ];
                path = [ tessdata-fast pkgs.poppler_utils pkgs.djvulibre ];

                serviceConfig = {
                  Type = "simple";
                  User = "root"; # Needed to read and write any scanning source folders configured in web app
                  StateDirectory = "librarian-web";
                  ExecStart = "${pkgs.bash}/bin/bash -c 'mkdir -p ${cfg.stateDir} && ln -sfn ${self.packages.${pkgs.system}.librarian-web}/lib/node_modules/librarian/dist ${cfg.stateDir}/dist && cd ${cfg.stateDir} && exec ${self.packages.${pkgs.system}.librarian-web}/bin/librarian-web'";
                  Restart = "on-failure";
                  EnvironmentFiles = lib.optional (cfg.apiKeyFile != null) cfg.apiKeyFile;
                };

                environment = {
                  PORT = toString cfg.port;
                  NODE_ENV = "production";
                  TESSDATA_PREFIX = "${tessdata-fast}/share/tessdata";
                };
              };

              networking.firewall.allowedTCPPorts = lib.optional cfg.openFirewall cfg.port;
            };
          };
      };
}

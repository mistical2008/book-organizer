# Grimmory: Ukrainian Book Cataloger & Folder Sorter 📚

Grimmory is a self-deactivating library organization daemon. It recursively scans unstructured folders of digital books (`.pdf`, `.djvu`, `.epub`, `.fb2`, `.mobi`) to sort them into nested directories structured by **`${Author} - ${Title} (${Year})`**.

This system implements a transactional, staged data fetching pipeline engineered to stay completely within free API tiers while preventing data loss or redos with durable SQLite state management.

---

## 🏗️ Multi-Table Staging Architecture

The core python script (`librarian.py`) stores runtime transactions across four local SQLite database tables inside `grimmory_state.db` to isolate side effects:

1. **`scanned_books`**: Stores all mapped file scopes with their detected local ISBN tags (if any) to prevent redundant OCR scanning if the service is interrupted.
2. **`isbn_requests`**: A dedicated queue tracking query records targeting the free **Google Books public API**.
3. **`ai_categorization`**: A dedicated queue containing book text samples waiting to be grouped and requested from **Gemini AI** as a fallback. Supports context optimization batch sizes.
4. **`file_organization`**: The definitive repository storing target destinations, parsed years, metadata categories, and confidence levels. This serves as the source of truth for the physical file copy stage.

---

## ⚡ The Standard 3-Phase Algorithm

```
📂 [Source Directory]
  │
  ├─── Phase 1: Directory Recursion & Pre-Filter Scan
  │     ├── OCR First 10 Pages (Tesseract UKR/ENG)
  │     ├── Match ISBN regex patterns
  │     └── Populate scanned_books -> (Queue isbn_requests OR ai_categorization)
  │
  ├─── Phase 2: Target Database Generation (Compilation)
  │     ├── Phase 2.1: Google Books ISBN Match (Free Direct Lookups)
  │     └── Phase 2.2: Gemini LLM Fallback (Context thinning & dynamic batch cache)
  │           └── Populate file_organization (Destinations & Structured Meta)
  │
  └─── Phase 3: Transactional Copy & Sidecar Sign-off
        ├── Create folders -> Copy book files
        ├── Write companion 'metadata.json' sidecars
        └── Self-destruct systemd timer dynamically when unprocessed queue is empty
```

---

## 🛠️ Step-by-Step Setup Instructions

### 1. Prerequisite Packages Installation

Make sure your machine has native document encoders and OCR packages installed:

#### Ubuntu / Debian Desktop & Server
```bash
# Install Tesseract OCR Engine and language support files (Ukrainian and English)
sudo apt-get update
sudo apt-get install -y tesseract-ocr tesseract-ocr-ukr tesseract-ocr-eng poppler-utils djvulibre-bin

# Install core python libraries
pip install google-genai opencv-python pillow pytesseract ebooklib beautifulsoup4 pypdf
```

#### NixOS (Declarative Module & Development Shell)

For NixOS systems, you can install the required packages on-the-fly using a development shell, or configure the service declarively in your system configurations.

##### A. Dynamic Shell Environment (`shell.nix`)
Create a `shell.nix` inside your repository directory to load all native packages and dependency libraries instantly environment-isolated:

```nix
{ pkgs ? import <nixpkgs> {} }:
pkgs.mkShell {
  buildInputs = with pkgs; [
    nodejs_20
    nodePackages.npm
    tesseract
    tesseract-ocr-eng
    tesseract-ocr-ukr
    poppler_utils
    djvulibre
    python311
    python311Packages.pytesseract
    python311Packages.pillow
    python311Packages.beautifulsoup4
    python311Packages.pypdf
  ];
}
```
Run `nix-shell` inside this folder to drop into the isolated terminal environment.

##### B. Declarative Configuration System Module
Add the dependencies and service configuration to `/etc/nixos/configuration.nix`:

```nix
{ config, pkgs, ... }: {
  # Add native packages to system profile
  environment.systemPackages = with pkgs; [
    tesseract
    tesseract-ocr-eng
    tesseract-ocr-ukr
    poppler_utils
    djvulibre
  ];

  # Define declarative systemd service matching Grimmory's custom daemon
  systemd.services.grimmory-librarian = {
    description = "Grimmory Library Metadata Organizer Daemon";
    after = [ "network.target" ];
    wantedBy = [ "multi-user.target" ];
    path = with pkgs; [ tesseract poppler_utils djvulibre python3 ];
    
    environment = {
      GEMINI_API_KEY = "your-api-key-here";
    };

    serviceConfig = {
      Type = "simple";
      User = "root";
      WorkingDirectory = "/var/lib/grimmory";
      ExecStart = "${pkgs.python3}/bin/python3 /var/lib/grimmory/librarian.py";
      Restart = "on-failure";
      RestartSec = "30s";
    };
  };

  # Define scanning trigger timer
  systemd.timers.grimmory-librarian = {
    description = "Run Grimmory Library Scan Periodically";
    timerConfig = {
      OnCalendar = "*-*-* *:00:00"; # every hour
      Persistent = true;
    };
    wantedBy = [ "timers.target" ];
  };
}
```

---

#### GNU Guix (Pure shell Environment & Shepherd Service)

GNU Guix focuses on purely functional package deployment. You can run Grimmory in a isolated container environment, or configure it on a Guix System as a native Shepherd service.

##### A. Ephemeral Pure Environment (`guix shell`)
Launch a sandboxed environment containing all native binary wrappers and dependencies with a single command:

```bash
guix shell --pure node tesseract tesseract-ukr tesseract-eng poppler djvulibre python python-pillow python-pytesseract python-beautifulsoup4 -- python3 librarian.py
```

##### B. Declarative System Service Configuration
Declare Grimmory as a custom Shepherd service inside your `/etc/config.scm` boot configuration file:

```scheme
(use-modules (gnu services)
             (gnu services shepherd)
             (gnu packages ocr)
             (gnu packages pdf)
             (gnu packages python)
             (gnu packages python-xyz))

(define grimmory-shepherd-service
  (shepherd-service
    (provision '(grimmory-librarian))
    (documentation "Grimmory Library Metadata Organizer Daemon active")
    (requirement '(networking))
    (start #~(make-forkexec-constructor
              (list (string-append #$python "/bin/python3")
                    "/var/lib/grimmory/librarian.py")
              #:environment-variables
              (list "GEMINI_API_KEY=your-api-key-here")
              #:directory "/var/lib/grimmory"
              #:user "root"))
    (stop #~(make-kill-destructor))))
```

---

#### Arch Linux (Native package configuration & systemd timers)

Arch Linux provides cutting-edge native packages along with community-maintained OCR engines in the Arch User Repository (AUR).

##### A. Install Native Packages from Official Repositories & AUR
Run the package manager to install the runtime dependencies:

```bash
# Update mirrors and install core utility packages
sudo pacman -Syu
sudo pacman -S tesseract tesseract-data-eng poppler djvulibre nodejs npm python python-pip python-pillow python-beautifulsoup4

# Install Ukrainian Tesseract training data from AUR 
# (You can use any AUR helper such as yay or paru)
yay -S tesseract-data-ukr
```

##### B. Running the Daemon Services via systemd
Once your dependencies are in place, copy your script, populate `/etc/systemd/system/grimmory-librarian.service` (see Section 3 below), and activate your timer:

```bash
# Reload systemd config
sudo systemctl daemon-reload

# Activate and start the polling timer
sudo systemctl enable --now grimmory-librarian.timer
```

---

### 2. Configure Your GEMINI API Key

To run AI categorization fallbacks, ensure the `GEMINI_API_KEY` is loaded inside your context shell environment:

```bash
# Temporary shell load
export GEMINI_API_KEY="your-api-key-here"

# To persist on login, add to .bashrc / .zshrc:
echo 'export GEMINI_API_KEY="your-api-key-here"' >> ~/.bashrc
```

---

### 3. Service Daemon Deployment (Systemd)

To deploy the daemon as an automated systemd timer:

1. Copy the customized `librarian.py` from the Web UI to your executable workspace:
    ```bash
    sudo mkdir -p /usr/local/bin/grimmory
    sudo cp librarian.py /usr/local/bin/grimmory/librarian.py
    sudo chmod +x /usr/local/bin/grimmory/librarian.py
    ```

2. Register the service module in `/etc/systemd/system/grimmory-librarian.service`:
    ```ini
    [Unit]
    Description=Grimmory Library Metadata Organizer Daemon
    After=network.target

    [Service]
    Type=simple
    User=root
    WorkingDirectory=/usr/local/bin/grimmory
    ExecStart=/usr/bin/python3 /usr/local/bin/grimmory/librarian.py
    Environment=GEMINI_API_KEY=your-api-key-here
    Restart=on-failure
    RestartSec=30s
    ```

3. Configure the periodic timer module in `/etc/systemd/system/grimmory-librarian.timer`:
    ```ini
    [Unit]
    Description=Run Grimmory Library Scan Periodically

    [Timer]
    OnCalendar=*-*-* *:00:00
    Persistent=true

    [Install]
    WantedBy=timers.target
    ```

4. Enable and boot the timer:
    ```bash
    sudo systemctl daemon-reload
    sudo systemctl enable --now grimmory-librarian.timer
    ```

5. Monitor runtime progress:
    ```bash
    # View system logs
    journalctl -u grimmory-librarian.service -f
    ```

---

## 🔮 Interactive Sandbox Testing

Open the **Live Sandbox** inside the Web application to simulate scanning, folder pickers, OCR runs, Google Books matching, Gemini context caching, and the live SQLite table grids in real-time under a fully localized client mockup dashboard!

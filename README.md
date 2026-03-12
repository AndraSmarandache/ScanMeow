# ScanMeow: Document Scanning and Management System

ScanMeow is a distributed system for document digitization and wireless management. It supports the transition from physical paper to digital formats through a mobile-desktop ecosystem, with high-quality document capture on mobile devices and secure transfer to a centralized desktop server via Bluetooth.

## System Architecture

The project is a monorepo with two main components:

- **Mobile Application** — Native Android app for image acquisition, document processing, and PDF generation.
- **Desktop Server** — Workstation interface that receives transfers and acts as a centralized document repository.

## Project Structure

```
ScanMeow/
├── mobile-app/          # Android Studio Project (Kotlin)
├── desktop-server/      # Desktop Interface and Receiver
└── docs/                # Technical specifications and Bluetooth protocols
```

## Features

### Mobile Component

- **Document Capture** — Interface for capturing high-resolution images of physical documents.
- **Image Processing** — Edge detection and contrast enhancement.
- **Format Conversion** — Conversion of processed images to standardized PDFs.
- **Bluetooth Client** — RFCOMM-based wireless file transmission to the server.

### Desktop Component

- **Service Listener** — Background process that listens for incoming Bluetooth connection requests.
- **File Management** — Automated organization of received documents.
- **Status Monitoring** — Visual feedback for connection establishment and transfer progress.

## Technical Stack

| Layer           | Technology                    |
|----------------|-------------------------------|
| Mobile Language| Kotlin                        |
| Mobile UI      | Jetpack Compose               |
| Architecture   | MVVM (Model-View-ViewModel)   |
| Local Storage  | SQLite / Room Persistence Library |
| Communication  | Bluetooth Classic (RFCOMM)    |
| Document Format| PDF / JPEG                    |

## Setup Instructions

### Mobile Setup

1. Open the `mobile-app` directory in Android Studio.
2. Sync the project with Gradle files.
3. Use a device with Bluetooth 4.0 or higher.

### Desktop Setup

1. Go to the `desktop-server` directory.
2. Install the required dependencies.
3. Ensure the machine has Bluetooth support.

## License

See the repository license file for terms of use.

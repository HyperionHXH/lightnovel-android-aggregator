import SwiftUI
import UIKit
import Photos
import ImageIO

private struct ReaderSharedImage: Identifiable { var id = UUID(); var image: UIImage }

struct ReaderImagePreview: View {
    @Environment(\.dismiss) private var dismiss
    let url: URL
    var initialActions: Bool = false
    @State private var image: UIImage?
    @State private var loading = false
    @State private var error: String?
    @State private var actions = false
    @State private var saving = false
    @State private var status: String?
    @State private var shared: ReaderSharedImage?
    @State private var attempt = UUID()
    @State private var imageWasReduced = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let image = image {
                ReaderZoomImage(image: image, onLongPress: { actions = true })
                    .accessibilityIdentifier("reader.image.preview")
            } else if loading { ProgressView("正在加载插图…").tint(.white).foregroundColor(.white) }
            else {
                VStack(spacing: 18) { Text(error ?? "图片无法加载").multilineTextAlignment(.center); Button("重试") { attempt = UUID() } }.foregroundColor(.white).padding()
            }
        }
        .safeAreaInset(edge: .top) {
            HStack { Spacer(); Button { dismiss() } label: { Image(systemName: "xmark").font(.title3).foregroundColor(.white).padding(14).background(Color.black.opacity(0.7)).clipShape(Circle()) }.accessibilityLabel("关闭图片").accessibilityIdentifier("reader.image.close") }.padding(12)
        }
        .safeAreaInset(edge: .bottom) {
            if actions || saving || status != nil {
                VStack(spacing: 12) {
                    if let status = status { Text(status).font(.callout) }
                    if imageWasReduced { Text("此大图已缩小至最长边 4096 像素；保存与分享使用当前图像。").font(.caption) }
                    if saving { ProgressView("正在保存到相册…").tint(.white) }
                    HStack {
                        Button("保存到相册", action: save).disabled(image == nil || saving).accessibilityIdentifier("reader.image.save")
                        Spacer()
                        Button("分享") { if let image = image { shared = ReaderSharedImage(image: image) } }.disabled(image == nil).accessibilityIdentifier("reader.image.share")
                        Spacer()
                        Button("收起") { actions = false; status = nil }.disabled(saving)
                    }
                }.foregroundColor(.white).padding(18).background(Color(white: 0.13).opacity(0.97))
            }
        }
        .sheet(item: $shared) { ShareSheet(items: [$0.image]) }
        .task(id: attempt) { await load() }
        .onAppear { actions = initialActions }
    }
    @MainActor private func load() async {
        loading = true; error = nil
        do {
            let bytes: Data
            if url.scheme?.lowercased() == "data" {
                guard let comma = url.absoluteString.firstIndex(of: ","), let data = Data(base64Encoded: String(url.absoluteString[url.absoluteString.index(after: comma)...])), data.count <= 24 * 1024 * 1024 else { throw SourceFailure.parsing("图片数据无效") }
                bytes = data
            } else {
                guard ReaderDocument.imageURL(url.absoluteString) != nil else { throw SourceFailure.parsing("图片地址不受支持") }
                let session = HTTPClient()
                bytes = try await session.bytes(url)
                guard bytes.count <= 32 * 1024 * 1024 else { throw SourceFailure.parsing("图片文件过大") }
            }
            try Task.checkCancellation()
            let decoded = await Task.detached(priority: .userInitiated) { () -> (UIImage, Bool)? in
                guard let source = CGImageSourceCreateWithData(bytes as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
                      let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
                      let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
                      let height = properties[kCGImagePropertyPixelHeight] as? NSNumber,
                      width.doubleValue > 0, height.doubleValue > 0,
                      width.doubleValue * height.doubleValue <= 1_000_000_000 else { return nil }
                let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                    kCGImageSourceCreateThumbnailWithTransform: true, kCGImageSourceThumbnailMaxPixelSize: 4096,
                    kCGImageSourceShouldCacheImmediately: true]
                guard let thumbnail = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
                return (UIImage(cgImage: thumbnail), max(width.intValue, height.intValue) > 4096)
            }.value
            try Task.checkCancellation()
            guard let decoded = decoded else { throw SourceFailure.parsing("图片格式无法识别或尺寸过大") }
            image = decoded.0; imageWasReduced = decoded.1; loading = false
        } catch is CancellationError { }
        catch { loading = false; error = error.localizedDescription }
    }
    private func save() {
        guard let image = image, !saving else { return }
        saving = true; status = nil
        Task { @MainActor in
            let authorization = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
            guard authorization == .authorized || authorization == .limited else {
                saving = false
                status = "未获得相册添加权限。请在系统设置中允许 Mixn 添加照片。"
                return
            }
            do {
                try await PHPhotoLibrary.shared().performChanges { PHAssetChangeRequest.creationRequestForAsset(from: image) }
                status = "已保存到相册"
            } catch { status = "保存失败：" + error.localizedDescription }
            saving = false
        }
    }
}

private struct ReaderZoomImage: UIViewRepresentable {
    var image: UIImage
    var onLongPress: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onLongPress: onLongPress) }
    func makeUIView(context: Context) -> UIScrollView {
        let scroll = UIScrollView()
        scroll.delegate = context.coordinator
        scroll.minimumZoomScale = 1
        scroll.maximumZoomScale = 6
        scroll.showsHorizontalScrollIndicator = false
        scroll.showsVerticalScrollIndicator = false
        scroll.backgroundColor = .black
        scroll.contentInsetAdjustmentBehavior = .never
        let view = UIImageView(image: image)
        view.contentMode = .scaleAspectFit
        view.isUserInteractionEnabled = true
        view.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(view)
        NSLayoutConstraint.activate([
            view.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            view.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            view.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            view.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            view.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor),
            view.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)
        ])
        context.coordinator.imageView = view
        let doubleTap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.zoom(_:)))
        doubleTap.numberOfTapsRequired = 2
        scroll.addGestureRecognizer(doubleTap)
        let long = UILongPressGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.longPress(_:)))
        long.minimumPressDuration = 0.5
        scroll.addGestureRecognizer(long)
        scroll.accessibilityLabel = "放大插图，双击缩放，长按显示保存与分享"
        scroll.accessibilityCustomActions = [UIAccessibilityCustomAction(name: "保存与分享", target: context.coordinator, selector: #selector(Coordinator.accessibilityActions))]
        return scroll
    }
    func updateUIView(_ uiView: UIScrollView, context: Context) {
        context.coordinator.onLongPress = onLongPress
        if context.coordinator.imageView?.image !== image { context.coordinator.imageView?.image = image; uiView.setZoomScale(1, animated: false) }
    }
    final class Coordinator: NSObject, UIScrollViewDelegate {
        weak var imageView: UIImageView?
        var onLongPress: () -> Void
        init(onLongPress: @escaping () -> Void) { self.onLongPress = onLongPress }
        func viewForZooming(in scrollView: UIScrollView) -> UIView? { imageView }
        @objc func zoom(_ tap: UITapGestureRecognizer) {
            guard let scroll = tap.view as? UIScrollView else { return }
            if scroll.zoomScale > 1 { scroll.setZoomScale(1, animated: true) }
            else {
                let point = tap.location(in: imageView)
                let target = CGRect(x: point.x - scroll.bounds.width / 6, y: point.y - scroll.bounds.height / 6, width: scroll.bounds.width / 3, height: scroll.bounds.height / 3)
                scroll.zoom(to: target, animated: true)
            }
        }
        @objc func longPress(_ gesture: UILongPressGestureRecognizer) { if gesture.state == .began { onLongPress() } }
        @objc func accessibilityActions() -> Bool { onLongPress(); return true }
    }
}

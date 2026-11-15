fn main() {
    if std::path::Path::new("assets/app.ico").exists() {
        embed_resource::compile("assets/app.rc", embed_resource::NONE)
            .manifest_required()
            .unwrap();
    }
}

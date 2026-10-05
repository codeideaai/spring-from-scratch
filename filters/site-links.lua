-- Keep repository Markdown navigation while linking back to the HTML home page.
function Link(link)
  if quarto.doc.is_format("html") and link.target == "../README.md" then
    -- English chapters return to their edition; Chinese links use its own home.
    local input = quarto.doc.input_file:gsub("\\", "/")
    if input:match("en/tutorial/[^/]+$") then
      link.target = "../index.html"
    else
      link.target = "../zh.html"
    end
  elseif quarto.doc.is_format("html") and link.target == "en/README.md" then
    link.target = "en/index.html"
  end
  return link
end

-- Plain Markdown stays compatible with GitHub's Mermaid renderer.
-- Reuse the pinned Quarto runtime's Mermaid library for the website.
function CodeBlock(block)
  if quarto.doc.is_format("html") and block.classes:includes("mermaid") then
    local share = os.getenv("QUARTO_SHARE_PATH")
    -- An absolute project path also works for pages nested under en/tutorial.
    local script_dir = pandoc.path.join({quarto.project.directory, "filters"})
    quarto.doc.add_html_dependency({
      name = "tutorial-mermaid",
      scripts = {
        pandoc.path.join({share, "formats", "html", "mermaid", "mermaid.min.js"}),
        pandoc.path.join({script_dir, "mermaid-init.js"})
      }
    })
    local text = block.text:gsub("&", "&amp;"):gsub("<", "&lt;"):gsub(">", "&gt;")
    return pandoc.RawBlock("html", '<pre class="mermaid">' .. text .. '</pre>')
  end
end

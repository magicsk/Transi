module.exports = {
  types: [
    { types: ["feat", "feature"], label: "Features" },
    { types: ["fix", "bugfix"], label: "Bugfixes" },
    { types: ["improvements", "enhancement"], label: "Improvements" },
    { types: ["perf"], label: "Performance Improvements" },
    { types: ["build", "ci"], label: "Build System" },
    { types: ["ref", "refactor"], label: "Refactors" },
    { types: ["doc", "docs"], label: "Documentation Changes" },
    { types: ["test", "tests"], label: "Tests" },
    { types: ["style"], label: "Code Style Changes" },
    { types: ["chore"], label: "Chores" },
    { types: ["other"], label: "Other Changes" },
  ],

  excludeTypes: ["ref", "refactor", "doc", "docs", "test", "tests", "style", "chore", "build", "ci"],

  renderTypeSection: function (label, commits) {
    let text = `\n## ${label}\n`;

    commits.forEach((commit) => {
      text += `- ${commit.subject}\n`;
    });

    return text;
  },

  renderChangelog: function (release, changes) {
    return changes;
  },
};